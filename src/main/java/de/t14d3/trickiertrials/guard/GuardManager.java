package de.t14d3.trickiertrials.guard;

import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.util.Keys;
import de.t14d3.trickiertrials.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Loads, ticks and saves Chamber Wardens (guards.yml) and seals the trials they protect. */
public final class GuardManager implements Listener {

    private final TrickierTrials plugin;
    private final File file;
    private final Map<String, Warden> wardens = new LinkedHashMap<>();
    private final Map<UUID, Long> lastNotice = new HashMap<>();
    private BukkitTask task;

    public GuardManager(TrickierTrials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "guards.yml");
        load();
    }

    // ───────────────────────────── Persistence ─────────────────────────────

    private void load() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = data.getConfigurationSection("wardens");
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(name);
            if (s == null) continue;
            World world = plugin.getServer().getWorld(s.getString("world", ""));
            Location post = parse(world, s.getString("post"));
            if (world == null || post == null) {
                plugin.getLogger().warning("Skipping warden '" + name + "': world or post is missing.");
                continue;
            }
            Warden warden = new Warden(plugin, name, post);
            for (String point : s.getStringList("waypoints")) {
                Location location = parse(world, point);
                if (location != null) warden.waypoints().add(location);
            }
            warden.openUntil(s.getLong("open-until", 0));
            wardens.put(name.toLowerCase(Locale.ROOT), warden);
        }
    }

    public void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Warden warden : wardens.values()) {
            String path = "wardens." + warden.name() + ".";
            data.set(path + "world", warden.post().getWorld().getName());
            data.set(path + "post", format(warden.post()));
            data.set(path + "waypoints", warden.waypoints().stream().map(GuardManager::format).toList());
            data.set(path + "open-until", warden.openUntil());
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save guards.yml: " + e.getMessage());
        }
    }

    private static String format(Location l) {
        return (l.getBlockX() + 0.5) + "," + l.getBlockY() + "," + (l.getBlockZ() + 0.5);
    }

    private static Location parse(World world, String raw) {
        if (world == null || raw == null) return null;
        String[] p = raw.split(",");
        if (p.length != 3) return null;
        try {
            return new Location(world, Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ───────────────────────────── Lifecycle ─────────────────────────────

    public void startTicking() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.settings().guardEnabled) return;
            for (Warden warden : wardens.values()) {
                boolean wasOpen = warden.isOpen();
                warden.tick();
                if (wasOpen != warden.isOpen()) save();
            }
        }, 20L, 10L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Warden warden : wardens.values()) warden.despawn();
        save();
    }

    public Collection<Warden> all() {
        return wardens.values();
    }

    public Warden get(String name) {
        return wardens.get(name.toLowerCase(Locale.ROOT));
    }

    public Warden create(String name, Location post) {
        Warden old = wardens.remove(name.toLowerCase(Locale.ROOT));
        if (old != null) old.despawn();
        Warden warden = new Warden(plugin, name, post.getBlock().getLocation().add(0.5, 0, 0.5));
        warden.waypoints().add(warden.post());
        wardens.put(name.toLowerCase(Locale.ROOT), warden);
        save();
        return warden;
    }

    public boolean remove(String name) {
        Warden warden = wardens.remove(name.toLowerCase(Locale.ROOT));
        if (warden == null) return false;
        warden.despawn();
        save();
        return true;
    }

    /** Re-seals the trials and brings the warden back immediately. */
    public void respawn(Warden warden) {
        warden.despawn();
        warden.openUntil(0);
        save();
    }

    /** Opens the trials without a fight (for testing). */
    public void open(Warden warden) {
        warden.despawn();
        warden.openUntil(System.currentTimeMillis() + plugin.settings().guardUnsealSeconds * 1000L);
        save();
    }

    /** The warden sealing this location, or null if it is free to use. */
    public Warden sealing(Location location) {
        if (!plugin.settings().guardEnabled) return null;
        for (Warden warden : wardens.values()) if (warden.seals(location)) return warden;
        return null;
    }

    /** Tells nearby players (at most every few seconds) why the trials don't start. */
    public void notifySealed(Warden warden, Collection<? extends Player> players) {
        long now = System.currentTimeMillis();
        for (Player player : players) {
            Long last = lastNotice.get(player.getUniqueId());
            if (last != null && now - last < 4000) continue;
            lastNotice.put(player.getUniqueId(), now);
            player.sendActionBar(Text.msg("warden-sealed", Text.ph("warden", warden.displayName())));
        }
    }

    private Warden wardenOf(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !Keys.isGuard(entity)) return null;
        for (Warden warden : wardens.values()) if (warden.isBoss(living)) return warden;
        return null;
    }

    // ───────────────────────────── Events ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWardenDeath(EntityDeathEvent event) {
        Warden warden = wardenOf(event.getEntity());
        if (warden == null) return;
        event.setDroppedExp(plugin.settings().bossExperience);
        warden.onDeath(event.getEntity().getKiller());
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWardenCombat(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity source = damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter ? shooter : damager;

        Warden hit = wardenOf(event.getEntity());
        if (hit != null && source instanceof Player player && event.getEntity() instanceof LivingEntity living) {
            hit.addDamage(player, Math.min(event.getFinalDamage(), living.getHealth() + living.getAbsorptionAmount()));
        }

        Warden attacker = wardenOf(source);
        if (attacker != null && event.getEntity() instanceof Player victim && attacker.boss() != null) attacker.boss().onMelee(victim);
    }

    /** Leftover wardens from before a restart or chunk reload are replaced by the live one. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (Keys.isGuard(entity) && wardenOf(entity) == null) entity.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSealedVault(PlayerInteractEvent event) {
        if (!plugin.settings().guardSealVaults || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.VAULT) return;
        Warden warden = sealing(event.getClickedBlock().getLocation());
        if (warden == null) return;
        event.setCancelled(true);
        event.getClickedBlock().getWorld().spawnParticle(Particle.SMOKE, event.getClickedBlock().getLocation().add(0.5, 1.1, 0.5), 8, 0.3, 0.1, 0.3, 0.01);
        lastNotice.remove(event.getPlayer().getUniqueId());
        notifySealed(warden, List.of(event.getPlayer()));
    }
}
