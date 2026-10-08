package de.t14d3.trickiertrials.chamber;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BoundingBox;

/** Helpers for locating generated trial chambers. */
public final class Chambers {

    private Chambers() {
    }

    /** Returns the bounding box of the generated trial chamber containing the location, or null. */
    public static BoundingBox chamberAt(Location location) {
        if (!location.isWorldLoaded() || !location.isChunkLoaded()) return null;
        for (GeneratedStructure structure : location.getChunk().getStructures(Structure.TRIAL_CHAMBERS)) {
            BoundingBox box = structure.getBoundingBox();
            if (box.contains(location.toVector())) return box;
        }
        return null;
    }

    public static boolean inChamber(Block block) {
        return chamberAt(block.getLocation().add(0.5, 0.5, 0.5)) != null;
    }
}
