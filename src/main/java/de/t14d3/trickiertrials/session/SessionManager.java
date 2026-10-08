package de.t14d3.trickiertrials.session;

import de.t14d3.trickiertrials.Settings;
import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.boss.BossType;
import de.t14d3.trickiertrials.boss.TrialBoss;
import de.t14d3.trickiertrials.chamber.Chambers;
import de.t14d3.trickiertrials.mob.MobScaler;
import de.t14d3.trickiertrials.util.Keys;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TrialSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.TrialSpawnerSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Creates, ticks and ends trial encounters and routes game events to them. */
public final class SessionManager implements Listener {

    private final TrickierTrials plugin;
    private final List<TrialSession> sessions = new ArrayList<>();
    private final Map<UUID, TrialSession> mobIndex = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private BukkitTask task;

    public SessionManager(TrickierTrials plugin) {
        this.plugin = plugin;
    }

    private Settings settings() {
        return plugin.settings();
    }

    public void startTicking() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (TrialSession session : List.copyOf(sessions)) session.tick();
            plugin.stats().saveAsync();
        }, TrialSession.TICK_INTERVAL, TrialSession.TICK_INTERVAL);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (TrialSession session : List.copyOf(sessions)) end(session, false);
        mobIndex.clear();
    }

    public Collection<TrialSession> sessions() {
        return List.copyOf(sessions);
    }

    public TrialSession sessionAt(Location location) {
        for (TrialSession session : sessions) if (session.contains(location)) return session;
        return null;
    }

    public TrialSession sessionOf(Player player) {
        for (TrialSession session : sessions) if (session.isPresent(player)) return session;
        return sessionAt(player.getLocation());
    }

    void index(LivingEntity entity, TrialSession session) {
        mobIndex.put(entity.getUniqueId(), session);
    }

    public void end(TrialSession session, boolean victory) {
        if (!sessions.remove(session)) return;
        session.finish(victory, true);
        mobIndex.values().removeIf(s -> s == session);
        if (victory && settings().victoryCooldown > 0) {
            cooldowns.put(session.regionKey(), System.currentTimeMillis() + settings().victoryCooldown * 1000L);
        }
    }

    // ───────────────────────────── Regions ─────────────────────────────

    private BoundingBox regionFor(Location spawner) {
        BoundingBox chamber = Chambers.chamberAt(spawner);
        if (chamber != null) return chamber.clone();
        double r = Math.max(8, settings().fallbackRadius);
        return BoundingBox.of(spawner, r, Math.max(8, r * 0.5), r);
    }

    private static String key(World world, BoundingBox box) {
        return world.getName() + ":" + (int) box.getMinX() + ":" + (int) box.getMinY() + ":" + (int) box.getMinZ();
    }

    private boolean onCooldown(String key) {
        Long until = cooldowns.get(key);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        cooldowns.remove(key);
        return false;
    }

    private TrialSession create(Location anchor) {
        BoundingBox region = regionFor(anchor);
        String key = key(anchor.getWorld(), region);
        if (onCooldown(key)) return null;
        TrialSession session = new TrialSession(plugin, this, anchor.getWorld(), region, key);
        sessions.add(session);
        return session;
    }

    /** Returns the encounter at the location, starting a new one if needed (used by admin commands). */
    public TrialSession getOrCreate(Location location) {
        TrialSession session = sessionAt(location);
        if (session != null) return session;
        session = create(location);
        if (session != null) session.start();
        return session;
    }

    /** Finds a standing spot near {@code origin}, falling back to the origin itself. */
    static Location safeSpotAround(Location origin, int radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 12; attempt++) {
            Location candidate = origin.clone().add(random.nextInt(-radius, radius + 1), 0, random.nextInt(-radius, radius + 1));
            for (int dy = 1; dy >= -2; dy--) {
                Block feet = candidate.clone().add(0, dy, 0).getBlock();
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(0, 1, 0).isPassable()
                        && feet.getRelative(0, -1, 0).getType().isSolid()) {
                    return feet.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return origin.getBlock().getType().isSolid() ? origin.clone().add(0, 1, 0) : origin.clone();
    }

    // ───────────────────────────── Events ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrialSpawn(TrialSpawnerSpawnEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        TrialSpawner spawner = event.getTrialSpawner();
        Location location = spawner.getLocation();
        Collection<Player> tracked = spawner.getTrackedPlayers();

        if (!settings().sessionsEnabled) {
            MobScaler.apply(entity, new MobScaler.Context(MobScaler.tierFor(tracked), 1, Math.max(1, tracked.size()), spawner.isOminous()), settings(), true);
            return;
        }

        TrialSession session = sessionAt(location);
        boolean fresh = false;
        if (session == null) {
            session = create(location);
            fresh = session != null;
        }
        if (session == null) {
            // Chamber was conquered recently: classic behaviour only.
            MobScaler.apply(entity, new MobScaler.Context(MobScaler.tierFor(tracked), 1, Math.max(1, tracked.size()), spawner.isOminous()), settings(), true);
            return;
        }
        session.recordSpawner(spawner.getBlock(), entity.getType(), spawner.isOminous());
        if (fresh) session.start();
        MobScaler.apply(entity, session.context(tracked), settings(), true);
        session.registerMob(entity);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || !Keys.isTrialMob(entity)) return;

        if (settings().restrictDrops) event.getDrops().removeIf(item -> !settings().allowedDrops.contains(item.getType()));

        boolean elite = !MobScaler.affixesOf(entity).isEmpty();
        boolean minion = entity.getPersistentDataContainer().has(Keys.MINION, PersistentDataType.BYTE);
        TrialSession session = mobIndex.remove(entity.getUniqueId());

        if (elite) {
            event.setDroppedExp(event.getDroppedExp() + settings().eliteBonusExp);
            if (ThreadLocalRandom.current().nextDouble() < settings().eliteKeyChance) event.getDrops().add(new ItemStack(Material.TRIAL_KEY));
            plugin.affixes().onEliteDeath(entity, child -> {
                if (session != null) session.registerMinion(child);
                else MobScaler.apply(child, new MobScaler.Context(MobScaler.Tier.DEFAULT, 1, 1, false), settings(), false);
            });
        }
        if (Keys.isBoss(entity)) event.setDroppedExp(0);
        if (session != null) session.onMobDeath(entity, entity.getKiller(), elite, minion);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        for (TrialSession session : sessions) {
            if (session.isPresent(player)) {
                session.onPlayerDeath(player);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager.getPersistentDataContainer().has(Keys.CELEBRATION, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) return;
        Entity source = damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter ? shooter : damager;
        if (!Keys.isBoss(source)) return;
        TrialSession session = mobIndex.get(source.getUniqueId());
        if (session != null && session.boss() != null && session.boss().entity().equals(source)) session.boss().onMelee(victim);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent event) {
        if (!Keys.isBoss(event.getEntity())) return;
        switch (event.getCause()) {
            case FALL, SUFFOCATION, DROWNING, CRAMMING, FLY_INTO_WALL, FIRE, FIRE_TICK, LAVA, FREEZE, HOT_FLOOR -> event.setCancelled(true);
            default -> {
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWebBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.COBWEB) return;
        for (TrialSession session : sessions) {
            TrialBoss boss = session.boss();
            if (boss != null && boss.isWeb(event.getBlock().getLocation())) {
                event.setDropItems(false);
                return;
            }
        }
    }

    /** Bosses that survived a crash or restart have no encounter any more - remove them. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (Keys.isBoss(entity) && !mobIndex.containsKey(entity.getUniqueId())) entity.remove();
        }
    }

    public TrialSession summonBoss(Player player, BossType type) {
        TrialSession session = sessionOf(player);
        if (session == null) session = getOrCreate(player.getLocation());
        if (session == null) return null;
        Location ahead = player.getLocation().add(player.getLocation().getDirection().setY(0).normalize().multiply(5));
        session.spawnBoss(type, safeSpotAround(ahead, 1));
        return session;
    }
}
