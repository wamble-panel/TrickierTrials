package de.t14d3.trickiertrials.chamber;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helpers for locating generated trial chambers. */
public final class Chambers {

    private Chambers() {
    }

    /** Structures never change, so each chunk's trial chamber boxes are looked up once and cached. */
    private static final Map<String, List<BoundingBox>> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<BoundingBox>> eldest) {
            return size() > 8192;
        }
    };

    /** Returns the bounding box of the generated trial chamber containing the location, or null. */
    public static BoundingBox chamberAt(Location location) {
        if (!location.isWorldLoaded() || !location.isChunkLoaded()) return null;
        String key = location.getWorld().getName() + ':' + (location.getBlockX() >> 4) + ':' + (location.getBlockZ() >> 4);
        List<BoundingBox> boxes = CACHE.get(key);
        if (boxes == null) {
            boxes = new ArrayList<>();
            for (GeneratedStructure structure : location.getChunk().getStructures(Structure.TRIAL_CHAMBERS)) {
                boxes.add(structure.getBoundingBox());
            }
            boxes = boxes.isEmpty() ? List.of() : List.copyOf(boxes);
            CACHE.put(key, boxes);
        }
        for (BoundingBox box : boxes) if (box.contains(location.toVector())) return box;
        return null;
    }

    public static boolean inChamber(Block block) {
        return chamberAt(block.getLocation().add(0.5, 0.5, 0.5)) != null;
    }
}
