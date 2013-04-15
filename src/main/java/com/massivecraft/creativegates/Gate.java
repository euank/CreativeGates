package com.massivecraft.creativegates;

import java.util.*;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import com.massivecraft.creativegates.util.BlockUtil;
import com.massivecraft.creativegates.util.SmokeUtil;
import com.massivecraft.creativegates.zcore.persist.*;
import com.massivecraft.creativegates.zcore.util.*;

public class Gate extends Entity implements Comparable<Gate>
{
    public static transient CreativeGates p = CreativeGates.p;

    public transient Set<WorldCoord> contentCoords;
    public transient Set<WorldCoord> frameCoords;
    public transient Set<Integer> frameMaterialIds;
    public transient Map<WorldCoord, Integer> danglingBlocks;
    public WorldCoord sourceCoord;
    public transient boolean frameDirIsNS; // True means NS direction. false means WE direction.

    private static transient final Set<BlockFace> expandFacesWE = new HashSet<BlockFace>();
    private static transient final Set<BlockFace> expandFacesNS = new HashSet<BlockFace>();
    static
    {
        expandFacesWE.add(BlockFace.UP);
        expandFacesWE.add(BlockFace.DOWN);
        expandFacesWE.add(BlockFace.WEST);
        expandFacesWE.add(BlockFace.EAST);

        expandFacesNS.add(BlockFace.UP);
        expandFacesNS.add(BlockFace.DOWN);
        expandFacesNS.add(BlockFace.NORTH);
        expandFacesNS.add(BlockFace.SOUTH);
    }

    public Gate()
    {
        this.dataClear();
    }

    /**
     * Is this gate open right now?
     */
    public boolean isOpen()
    {
        return Gates.i.findFrom(sourceCoord) != null;
    }

    public void open() throws GateOpenException
    {
        Block sourceBlock = sourceCoord.getBlock();

        if (this.isOpen()) return;

        // TODO: THE NULL CHECK IS OK?
        if (sourceBlock == null || sourceBlock.getTypeId() != Conf.block)
        {
            throw new GateOpenException(p.txt.parse(Lang.openFailWrongSourceMaterial, TextUtil.getMaterialName(Conf.block)));
        }

        this.dataPopulate();

        // Finally we set the content blocks material to water
        this.fill();
    }

    public void close()
    {
        this.empty();
        this.detach();
    }

    /**
     * This method is used to check the gate on use as a safety measure.
     * If a player walks through a non intact gate, the frame was probably destroyed by a super pick axe.
     */
    public boolean isIntact()
    {
        if (this.sourceCoord.getBlock().getTypeId() != Conf.block)
        {
            return false;
        }

        for (WorldCoord coord : frameCoords)
        {
            if (this.sourceCoord.equals(coord))
            {
                continue;
            }

            Block block = coord.getBlock();
            if ( ! this.frameMaterialIds.contains(block.getTypeId())) return false;
        }

        for(WorldCoord coord : danglingBlocks.keySet())
        {
            if(coord.getBlock().getTypeId() != danglingBlocks.get(coord)) return false;

        }

        return true;
    }

    /**
     * This method clears the "data" (coords and material ids).
     */
    public void dataClear()
    {
        danglingBlocks = new HashMap<WorldCoord, Integer>();
        contentCoords = new HashSet<WorldCoord>();
        frameCoords = new HashSet<WorldCoord>();
        frameMaterialIds = new TreeSet<Integer>();
    }

    /**
     * This method populates the "data" (coords and material ids).
     * It will return false if there was no possible frame.
     */
    public void dataPopulate() throws GateOpenException
    {
        this.dataClear();
        Block sourceBlock = sourceCoord.getBlock();

        // Search for content WE and NS
        Block floodStartBlock = sourceBlock.getRelative(BlockFace.UP);
        Set<Block> contentBlocksWE = getFloodBlocks(floodStartBlock, new HashSet<Block>(), expandFacesWE);
        Set<Block> contentBlocksNS = getFloodBlocks(floodStartBlock, new HashSet<Block>(), expandFacesNS);

        // Figure out dir and content... or throw no frame fail.
        Set<Block> contentBlocks;

        if (contentBlocksWE == null && contentBlocksNS == null)
        {
            throw new GateOpenException(p.txt.parse(Lang.openFailNoFrame));
        }

        if (contentBlocksNS == null)
        {
            contentBlocks = contentBlocksWE;
            frameDirIsNS = false;
        }
        else if (contentBlocksWE == null)
        {
            contentBlocks = contentBlocksNS;
            frameDirIsNS = true;
        }
        else if (contentBlocksWE.size() > contentBlocksNS.size())
        {
            contentBlocks = contentBlocksNS;
            frameDirIsNS = true;
        }
        else
        {
            contentBlocks = contentBlocksWE;
            frameDirIsNS = false;
        }


        // Find the frame blocks and materials
        Set<Block> frameBlocks = new HashSet<Block>();
        Set<BlockFace> expandFaces = frameDirIsNS ? expandFacesNS : expandFacesWE;
        for (Block currentBlock : contentBlocks)
        {
            for (BlockFace face : expandFaces)
            {
                Block potentialBlock = currentBlock.getRelative(face);
                if ( ! contentBlocks.contains(potentialBlock))
                {
                    frameBlocks.add(potentialBlock);
                    if (potentialBlock != sourceBlock)
                    {
                        frameMaterialIds.add(potentialBlock.getTypeId());
                    }
                }
            }
        }

        Map<WorldCoord, Integer> dangling = getDanglingBlocks(sourceBlock);

        if(dangling == null)
        {
            throw new GateOpenException(p.txt.parse(Lang.openFailNoDangling));
        }

        // Now we add the frame and content blocks as world coords to the lookup maps.
        for (Block frameBlock : frameBlocks)
        {
            this.frameCoords.add(new WorldCoord(frameBlock));
        }
        for (Block contentBlock : contentBlocks)
        {
            this.contentCoords.add(new WorldCoord(contentBlock));
        }
        for(WorldCoord danglingBlock : dangling.keySet())
        {
            this.danglingBlocks.put(danglingBlock, dangling.get(danglingBlock));
        }
    }


    //----------------------------------------------//
    // Find Target Gate And Location
    //----------------------------------------------//

    /*
     * This method finds the place where this gates goes to.
     * We pick the next gate in the network chain that has a non blocked exit.
     */
    public Gate getMyTargetGate()
    {
        for (Gate gate : this.getSelfRelativeGatePath())
        {
            if (gate != null) return gate;
        }
        return null;
    }

    /*
     * Find all the gates on the network of this gate (including this gate itself).
     * The gates on the same network are those with the same frame materials.
     */
    public List<Gate> getNetworkGatePath()
    {
        List<Gate> networkGatePath = new ArrayList<Gate>();

        // We put the gates in a tree set to sort them after gate location.
        TreeSet<Gate> gates = new TreeSet<Gate>();
        gates.addAll(Gates.i.get());

        for (Gate gate : gates)
        {
            if (this.frameMaterialIds.equals(gate.frameMaterialIds))
            {
                networkGatePath.add(gate);
            }
        }

        return networkGatePath;
    }

    /*
     * Return the gates on the network in the order they come after this gate.
     * This gate itself is not included (as opposed to getNetworkGatePath where it is).
     * This gate itself would be in the beginning / end of this path.
     */
    public List<Gate> getSelfRelativeGatePath()
    {
        List<Gate> selfRelativeGatePath = new ArrayList<Gate>();

        List<Gate> networkGatePath = this.getNetworkGatePath();
        int myIndex = networkGatePath.indexOf(this);

        // Add what is after me
        selfRelativeGatePath.addAll(networkGatePath.subList(myIndex+1, networkGatePath.size()));

        // Add what is before me
        selfRelativeGatePath.addAll(networkGatePath.subList(0, myIndex));

        return selfRelativeGatePath;
    }

    /*
     * If someone arrives to this gate, where should we place them?
     * This method returns a Location telling us just that.
     * It might also return null if the gate exit is blocked.
     */
    public Location getMyOwnExitLocation()
    {
        Block overSourceBlock = sourceCoord.getBlock().getRelative(BlockFace.UP);
        Location firstChoice;
        Location secondChoice;

        if (frameDirIsNS)
        {
            firstChoice = overSourceBlock.getRelative(BlockFace.EAST).getLocation();
            firstChoice.setYaw(270);

            secondChoice = overSourceBlock.getRelative(BlockFace.WEST).getLocation();
            secondChoice.setYaw(90);
        }
        else
        {
            firstChoice = overSourceBlock.getRelative(BlockFace.NORTH).getLocation();
            firstChoice.setYaw(180);

            secondChoice = overSourceBlock.getRelative(BlockFace.SOUTH).getLocation();
            secondChoice.setYaw(0);
        }

        // We want to stand in the middle of the block. Not in the corner.
        firstChoice.add(0.5, 0, 0.5);
        secondChoice.add(0.5, 0, 0.5);

        firstChoice.setPitch(0);
        secondChoice.setPitch(0);

        if (BlockUtil.canPlayerStandInBlock(firstChoice.getBlock()))
        {
            return firstChoice;
        }
        else if (BlockUtil.canPlayerStandInBlock(secondChoice.getBlock()))
        {
            return secondChoice;
        }

        return null;
    }

    //----------------------------------------------//
    // Gate information
    //----------------------------------------------//

    public String getInfoMsgMaterial()
    {
        ArrayList<String> materialNames = new ArrayList<String>();
        for (Integer frameMaterialId : this.frameMaterialIds)
        {
            materialNames.add(p.txt.parse("<h>") + TextUtil.getMaterialName(Material.getMaterial(frameMaterialId)));
        }

        String materials = TextUtil.implode(materialNames, p.txt.parse("<i>, "));

        return p.txt.parse(Lang.infoMaterials, materials);
    }

    public String getInfoMsgNetwork()
    {
        return p.txt.parse(Lang.infoGateCount, this.getNetworkGatePath().size());
    }

    public void informPlayer(Player player)
    {
        player.sendMessage("");
        player.sendMessage(this.getInfoMsgMaterial());
        player.sendMessage(this.getInfoMsgNetwork());
    }

    //----------------------------------------------//
    // Content management
    //----------------------------------------------//

    public void fill()
    {
        for (WorldCoord coord : this.contentCoords)
        {
            coord.getBlock().setType(Material.STATIONARY_WATER);
        }
    }

    public void empty()
    {
        for (WorldCoord coord : this.contentCoords)
        {
            coord.getBlock().setType(Material.AIR);
        }
    }

    //----------------------------------------------//
    // Flood
    //----------------------------------------------//

    public static Set<Block> getFloodBlocks(Block startBlock, Set<Block> foundBlocks, Set<BlockFace> expandFaces)
    {
        if (foundBlocks == null)
        {
            return null;
        }

        if  (foundBlocks.size() > Conf.maxarea)
        {
            return null;
        }

        if (foundBlocks.contains(startBlock))
        {
            return foundBlocks;
        }

        if (startBlock.getType() == Material.AIR || startBlock.getType() == Material.WATER || startBlock.getType() == Material.STATIONARY_WATER)
        {
            // ... We found a block :D ...
            foundBlocks.add(startBlock);

            // ... And flood away !
            for (BlockFace face : expandFaces)
            {
                Block potentialBlock = startBlock.getRelative(face);
                foundBlocks = getFloodBlocks(potentialBlock, foundBlocks, expandFaces);
            }
        }

        return foundBlocks;
    }

    //----------------------------------------------//
    // Dangling
    //----------------------------------------------//

    public static Map<WorldCoord, Integer> getDanglingBlocks(Block keyBlock) {
        Map<WorldCoord, Integer> res = new HashMap<WorldCoord, Integer>();
        Block centerBlock = keyBlock;
        int correctId;
        for(int y = 0; y < Conf.danglingHeight; y++)
        {
            centerBlock = centerBlock.getRelative(BlockFace.DOWN);

            for(int x = 0; x < (2*Conf.danglingRadius+1); x++)
            {
                for(int z = 0; z < (2*Conf.danglingRadius+1); z++)
                {
                    Block thisBlock = centerBlock.getRelative(BlockFace.EAST, (x - (Conf.danglingRadius)));
                    thisBlock = thisBlock.getRelative(BlockFace.NORTH, z - Conf.danglingRadius);

                    correctId = Conf.danglingBlocks[
                                    y * (Conf.danglingRadius * 2 + 1) * (Conf.danglingRadius * 2 + 1) +
                                    (z) * (Conf.danglingRadius * 2 + 1) +
                                    (x)];

                    if(correctId >= 0 && correctId != thisBlock.getTypeId())
                    {
                        return null;
                    }
                    res.put(new WorldCoord(thisBlock.getLocation()), thisBlock.getTypeId());
                }
            }
        }
        return res;

    }

    //----------------------------------------------//
    // Special FX
    //----------------------------------------------//
    public void emmitSmoke()
    {
        List<Location> smokeLocations = new ArrayList<Location>();
        for (WorldCoord coord : this.contentCoords)
        {
            smokeLocations.add(coord.getLocation());
        }
        SmokeUtil.emmitFromLocations(smokeLocations);
    }

    //----------------------------------------------//
    // Comparable
    //----------------------------------------------//

    @Override
    public int compareTo(Gate o)
    {
        return this.sourceCoord.toString().compareTo(o.sourceCoord.toString());
    }
}
