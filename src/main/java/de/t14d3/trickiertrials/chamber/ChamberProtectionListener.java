package de.t14d3.trickiertrials.chamber;

import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.Settings;
import io.papermc.paper.event.block.BlockBreakProgressUpdateEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Keeps trial chambers intact: broken blocks regrow, placed blocks decay. */
public final class ChamberProtectionListener implements Listener {

    private final TrickierTrials plugin;
    private final Map<Location, BlockData> brokenBlocks = new HashMap<>();
    private final Map<Location, BlockData> placedBlocks = new HashMap<>();

    public ChamberProtectionListener(TrickierTrials plugin) {
        this.plugin = plugin;
    }

    private Settings settings() {
        return plugin.settings();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!settings().protectionEnabled) return;
        Block block = event.getBlock();
        Location location = block.getLocation();

        // A block a player placed themselves is just removed again.
        if (placedBlocks.remove(location) != null) return;

        if (!settings().regenerateBrokenBlocks || !settings().protectedBlocks.contains(block.getType())) return;
        if (!Chambers.inChamber(block)) return;

        event.setDropItems(false);
        BlockData original = block.getBlockData().clone();
        brokenBlocks.put(location, original);
        long delay = settings().regenerateDelay * 20L + ThreadLocalRandom.current().nextLong(100);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> restore(location), delay);
    }

    private void restore(Location location) {
        BlockData data = brokenBlocks.remove(location);
        if (data == null || placedBlocks.containsKey(location)) return;
        Block block = location.getBlock();
        if (!block.getType().isAir() && block.getType() != Material.CAVE_AIR && !block.isReplaceable()) return;
        block.setBlockData(data, false);
        location.getWorld().spawnParticle(Particle.WAX_OFF, location.clone().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!settings().protectionEnabled || !settings().decayPlacedBlocks) return;
        Block block = event.getBlock();
        if (!Chambers.inChamber(block)) return;

        Location location = block.getLocation();
        BlockData placed = block.getBlockData().clone();
        placedBlocks.put(location, placed);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (placedBlocks.remove(location) == null) return;
            Block current = location.getBlock();
            if (current.getType() != placed.getMaterial()) return;
            BlockData restored = brokenBlocks.remove(location);
            current.setBlockData(restored != null ? restored : Material.AIR.createBlockData(), false);
            location.getWorld().spawnParticle(Particle.POOF, location.clone().add(0.5, 0.5, 0.5), 4, 0.2, 0.2, 0.2, 0.01);
        }, settings().decayDelay * 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreakProgress(BlockBreakProgressUpdateEvent event) {
        int level = settings().miningFatigueLevel;
        if (!settings().protectionEnabled || level <= 0) return;
        if (!settings().protectedBlocks.contains(event.getBlock().getType())) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!Chambers.inChamber(event.getBlock())) return;
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 10, level - 1, true, false, false));
    }

    /** Puts every pending block back immediately (used on shutdown). */
    public void restoreAll() {
        for (Map.Entry<Location, BlockData> entry : Map.copyOf(placedBlocks).entrySet()) {
            Block block = entry.getKey().getBlock();
            if (block.getType() == entry.getValue().getMaterial()) block.setType(Material.AIR, false);
        }
        placedBlocks.clear();
        for (Location location : Map.copyOf(brokenBlocks).keySet()) restore(location);
        brokenBlocks.clear();
    }
}
