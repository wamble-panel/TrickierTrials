package de.t14d3.trickiertrials.session;

import de.t14d3.trickiertrials.Settings;
import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.boss.BossHost;
import de.t14d3.trickiertrials.boss.BossType;
import de.t14d3.trickiertrials.boss.TrialBoss;
import de.t14d3.trickiertrials.mob.Affix;
import de.t14d3.trickiertrials.mob.MobScaler;
import de.t14d3.trickiertrials.util.Fx;
import de.t14d3.trickiertrials.util.RankBadge;
import de.t14d3.trickiertrials.util.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TrialSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One trial encounter: a chamber (or an area around a custom trial spawner) with waves, boss waves,
 * combos, a boss bar, an action bar HUD and an end-of-run summary.
 */
public final class TrialSession implements BossHost {

    public enum State { WAVE, BOSS, INTERMISSION, ENDED }

    static final int TICK_INTERVAL = 10;
    private static final int STALL_TICKS = 45 * 20;

    private final TrickierTrials plugin;
    private final SessionManager manager;
    private final World world;
    private final BoundingBox region;
    private final String regionKey;
    private final long startedAt = System.currentTimeMillis();

    private final Map<Location, EntityType> spawners = new HashMap<>();
    private final Map<UUID, PlayerRun> runs = new LinkedHashMap<>();
    private final Set<UUID> present = new LinkedHashSet<>();
    private final Set<UUID> mobs = new HashSet<>();
    private final BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);

    private State state = State.WAVE;
    private boolean ominous;
    private int wave;
    private int waveKills;
    private int waveTarget = 1;
    private int intermissionTicks;
    private int idleTicks;
    private int emptyTicks;
    private boolean reinforcedThisWave;
    private boolean awakenedThisWave;
    private final Set<Location> rewardedSpawners = new HashSet<>();
    private long ticks;
    private long lastProgressTick;
    private long teamScore;
    private TrialBoss boss;
    private BossType lastBoss;

    // Replayability
    private int rank;
    private final Set<Modifier> modifiers = java.util.EnumSet.noneOf(Modifier.class);
    private WaveEvent event;
    private WaveEvent lastEvent;
    private int eventTicks;
    private UUID treasure;
    private int darknessTicks;

    TrialSession(TrickierTrials plugin, SessionManager manager, World world, BoundingBox region, String regionKey) {
        this.plugin = plugin;
        this.manager = manager;
        this.world = world;
        this.region = region;
        this.regionKey = regionKey;
    }

    private Settings settings() {
        return plugin.settings();
    }

    // ───────────────────────────── Queries ─────────────────────────────

    @Override
    public TrickierTrials plugin() {
        return plugin;
    }

    @Override
    public Collection<Player> players() {
        List<Player> list = new ArrayList<>(present.size());
        for (UUID uuid : present) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) list.add(player);
        }
        return list;
    }

    public boolean contains(Location location) {
        return location.getWorld() != null && location.getWorld().equals(world) && region.contains(location.toVector());
    }

    public boolean isPresent(Player player) {
        return present.contains(player.getUniqueId());
    }

    public String regionKey() {
        return regionKey;
    }

    public State state() {
        return state;
    }

    public int wave() {
        return wave;
    }

    public boolean ominous() {
        return ominous;
    }

    public long teamScore() {
        return teamScore;
    }

    public TrialBoss boss() {
        return boss;
    }

    public long elapsed() {
        return System.currentTimeMillis() - startedAt;
    }

    public int rank() {
        return rank;
    }

    public Set<Modifier> modifiers() {
        return modifiers;
    }

    public boolean has(Modifier modifier) {
        return modifiers.contains(modifier);
    }

    /** Score multiplier from modifiers, the current wave event and the Trial Rank. */
    private double scoreMultiplier() {
        double multiplier = 1 + settings().rankScoreBonus * rank;
        for (Modifier modifier : modifiers) multiplier *= modifier.scoreMultiplier();
        if (event == WaveEvent.ELITE_SURGE) multiplier *= 1.5;
        return multiplier;
    }

    /** Trial Key reward multiplier from the Trial Rank and the Golden modifier. */
    private double rewardMultiplier() {
        return (1 + settings().rankRewardBonus * rank) * (has(Modifier.GOLDEN) ? 2 : 1);
    }

    private int comboWindow() {
        return has(Modifier.MOMENTUM) ? (int) (settings().comboWindowTicks * 1.5) : settings().comboWindowTicks;
    }

    private double comboMax() {
        return settings().comboMax + (has(Modifier.MOMENTUM) ? 1 : 0);
    }

    public PlayerRun run(Player player) {
        return runs.get(player.getUniqueId());
    }

    private int playerCount() {
        return Math.max(1, present.size());
    }

    private boolean isFinalWave(int number) {
        return settings().finalWave > 0 && number >= settings().finalWave;
    }

    private boolean isBossWave(int number) {
        if (!settings().bossesEnabled || !anyBossEnabled()) return false;
        if (isFinalWave(number)) return true;
        return settings().bossEvery > 0 && number % settings().bossEvery == 0;
    }

    private boolean anyBossEnabled() {
        for (BossType type : BossType.values()) if (bossAllowed(type)) return true;
        return false;
    }

    /** A boss (and its minions) must be a trial chamber mob from the configured pool. */
    private boolean bossAllowed(BossType type) {
        return settings().bossEnabled(type.id()) && settings().mobPool.contains(type.entityType()) && settings().mobPool.contains(type.minionType());
    }

    private String finalSuffix() {
        return settings().finalWave > 0 ? "/" + Text.roman(settings().finalWave) : "";
    }

    // ───────────────────────────── Lifecycle ─────────────────────────────

    void start() {
        discoverSpawners();
        updatePresence(false);
        wave = 1;
        Collection<Player> players = players();
        rank = partyRank(players);
        rollModifiers();
        if (settings().titles) {
            Text.title(Audience.audience(players), ominous ? "session-start-title-ominous" : "session-start-title", "session-start-subtitle", 10, 50, 15,
                    Text.ph("waves", settings().finalWave > 0 ? String.valueOf(settings().finalWave) : "∞"),
                    Text.ph("players", players.size()), Text.ph("rank", rankLabel()), Text.ph("stars", RankBadge.component(rank)));
        }
        announceModifiers(players);
        Fx.sound(players, ominous ? Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE : Sound.BLOCK_TRIAL_SPAWNER_DETECT_PLAYER, 1f, 0.8f);
        beginWave(false);
    }

    private void beginWave(boolean announce) {
        reinforcedThisWave = false;
        awakenedThisWave = false;
        emptyTicks = 0;
        lastProgressTick = ticks;
        discoverSpawners();
        if (settings().wakeSpawners && !isBossWave(wave)) wakeSpawners(false);
        Collection<Player> players = players();
        if (isBossWave(wave)) {
            state = State.BOSS;
            spawnBoss(pickBossType(), bossLocation());
            return;
        }
        state = State.WAVE;
        waveKills = 0;
        waveTarget = settings().killsBase + settings().killsPerExtraPlayer * (playerCount() - 1) + settings().killsPerWave * (wave - 1);
        rollWaveEvent();
        if (event == WaveEvent.SWARM) waveTarget = (int) Math.round(waveTarget * 1.5);
        if (event == WaveEvent.BLITZ) eventTicks = (settings().blitzSeconds + waveTarget * 3) * 20;
        if (event == WaveEvent.TREASURE) spawnTreasure();
        if (announce && settings().titles) {
            if (event != null) {
                Text.title(Audience.audience(players), "wave-start-title", "wave-event-subtitle", 5, 45, 10,
                        Text.ph("wave", Text.roman(wave)), Text.ph("event", Text.parse(eventLabel())), Text.ph("description", event.description()));
            } else {
                Text.title(Audience.audience(players), "wave-start-title", "wave-start-subtitle", 5, 35, 10,
                        Text.ph("wave", Text.roman(wave)), Text.ph("target", waveTarget));
            }
        }
        if (event != null) {
            Component message = Text.prefixed("wave-event", Text.ph("event", Text.parse(eventLabel())), Text.ph("description", event.description()));
            for (Player player : players) player.sendMessage(message);
        }
        if (announce) Fx.sound(players, event != null ? Sound.BLOCK_BELL_RESONATE : Sound.EVENT_RAID_HORN, 0.6f, 1.2f);
    }

    // ───────────────────────────── Rank, modifiers & events ─────────────────────────────

    /** The party's Trial Rank: the average rank of everyone present when the trial starts. */
    private int partyRank(Collection<Player> players) {
        if (!settings().progressionEnabled || players.isEmpty()) return 0;
        double total = 0;
        for (Player player : players) total += plugin.stats().rank(player.getUniqueId());
        return (int) Math.min(settings().maxRank, Math.round(total / players.size()));
    }

    private String rankLabel() {
        return rank <= 0 ? "-" : Text.roman(rank);
    }

    private void rollModifiers() {
        modifiers.clear();
        if (!settings().modifiersEnabled) return;
        int count = Math.min(settings().modifiersMax, settings().modifiersBase + (settings().modifiersPerRanks > 0 ? rank / settings().modifiersPerRanks : 0));
        List<Modifier> pool = new ArrayList<>();
        for (Modifier modifier : Modifier.values()) if (!settings().disabledModifiers.contains(modifier.name())) pool.add(modifier);
        java.util.Collections.shuffle(pool);
        for (int i = 0; i < Math.min(count, pool.size()); i++) modifiers.add(pool.get(i));
    }

    private String modifierList() {
        if (modifiers.isEmpty()) return "<muted>none</muted>";
        return String.join("<dark_gray>, </dark_gray>", modifiers.stream().map(Modifier::mini).toList());
    }

    private void announceModifiers(Collection<Player> players) {
        Component message = Text.parse(Text.raw("prefix") + Text.raw("session-modifiers"),
                Text.ph("rank", rankLabel()), Text.ph("stars", RankBadge.component(rank)), Text.ph("modifiers", Text.parse(modifierList())));
        for (Player player : players) player.sendMessage(message);
    }

    private void rollWaveEvent() {
        event = null;
        treasure = null;
        if (wave < 2 || ThreadLocalRandom.current().nextDouble() >= settings().eventChance) return;
        List<WaveEvent> pool = new ArrayList<>();
        for (WaveEvent candidate : WaveEvent.values()) {
            if (candidate != lastEvent && !settings().disabledEvents.contains(candidate.name())) pool.add(candidate);
        }
        if (pool.isEmpty()) return;
        event = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        lastEvent = event;
    }

    private String eventLabel() {
        return event == null ? "" : "<" + event.color() + "><bold>" + event.displayName() + "</bold></" + event.color() + ">";
    }

    private void spawnTreasure() {
        Collection<Player> players = players();
        if (players.isEmpty()) return;
        List<Location> nearby = new ArrayList<>();
        for (Location spawner : spawners.keySet()) if (nearPlayers(spawner, 24)) nearby.add(spawner);
        Location origin = !nearby.isEmpty() ? nearby.get(ThreadLocalRandom.current().nextInt(nearby.size())).clone().add(0.5, 0, 0.5)
                : players.iterator().next().getLocation();
        Location location = SessionManager.safeSpotAround(origin, 3);
        LivingEntity breeze = world.spawn(location, org.bukkit.entity.Breeze.class, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
            e.customName(Text.parse("<gradient:gold:copper><bold>✦ Treasure Breeze ✦</bold></gradient>"));
            e.setCustomNameVisible(true);
            e.setGlowing(true);
            MobScaler.scaleHealth(e, 1.5);
            Affix.multiply(e, Attribute.MOVEMENT_SPEED, 1.6);
            de.t14d3.trickiertrials.util.Keys.markTrialMob(e);
            e.getPersistentDataContainer().set(de.t14d3.trickiertrials.util.Keys.MINION, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
        world.spawnParticle(Particle.WAX_ON, location.clone().add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0.1);
        Fx.worldSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 1.4f);
        treasure = breeze.getUniqueId();
        manager.index(breeze, this);
        eventTicks = settings().treasureSeconds * 20;
    }

    private void tickEvent() {
        if (event == null) return;
        if (event == WaveEvent.BLITZ && eventTicks > 0) {
            eventTicks -= TICK_INTERVAL;
            if (eventTicks <= 0) {
                for (Player player : players()) Text.send(player, "blitz-failed");
                Fx.sound(players(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1f);
            }
        }
        if (event == WaveEvent.TREASURE && treasure != null) {
            eventTicks -= TICK_INTERVAL;
            Entity breeze = plugin.getServer().getEntity(treasure);
            if (breeze != null && breeze.isValid()) {
                world.spawnParticle(Particle.WAX_ON, breeze.getLocation().add(0, 1, 0), 3, 0.3, 0.4, 0.3, 0);
            }
            if (eventTicks <= 0 || breeze == null || !breeze.isValid()) {
                if (breeze != null && breeze.isValid()) {
                    world.spawnParticle(Particle.GUST_EMITTER_SMALL, breeze.getLocation(), 1);
                    breeze.remove();
                }
                treasure = null;
                for (Player player : players()) Text.send(player, "treasure-escaped");
            }
        }
    }

    private void tickModifiers() {
        if (!has(Modifier.DARKNESS)) return;
        darknessTicks += TICK_INTERVAL;
        if (darknessTicks < 25 * 20) return;
        darknessTicks = 0;
        for (Player player : players()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 140, 0, false, false, true));
        }
        Fx.sound(players(), Sound.AMBIENT_CAVE, 1f, 0.8f);
    }

    public void spawnBoss(BossType type, Location location) {
        if (boss != null) {
            boss.cleanup(true);
            hideBossBar();
        }
        state = State.BOSS;
        lastBoss = type;
        double strength = (1 + settings().rankBossHealth * rank) * (has(Modifier.FORTIFIED) ? 1.25 : 1) * (has(Modifier.GOLDEN) ? 1.25 : 1);
        boss = TrialBoss.spawn(this, type, location, playerCount(), ominous, isFinalWave(wave), strength);
        manager.index(boss.entity(), this);
        List<Affix> empowered = empowerBoss();
        Collection<Player> players = players();
        for (Player player : players) player.showBossBar(boss.bar());
        if (settings().titles) {
            Component affixes = empowered.isEmpty() ? Component.empty()
                    : Text.parse(" <dark_gray>·</dark_gray> " + String.join(" ", empowered.stream()
                    .map(a -> "<" + a.color().asHexString() + ">" + a.displayName() + "</" + a.color().asHexString() + ">").toList()));
            Text.title(Audience.audience(players), "boss-incoming-title", "boss-incoming-subtitle", 5, 50, 15,
                    Text.ph("boss", boss.name()), Text.ph("affixes", affixes));
        }
        Fx.sound(players, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.9f);

        // The boss climbs out of the nearest trial spawner.
        spawners.keySet().stream()
                .filter(s -> s.getWorld().equals(location.getWorld()) && s.distanceSquared(location) < 6 * 6)
                .min(Comparator.comparingDouble(s -> s.distanceSquared(location)))
                .ifPresent(spawner -> {
                    Location center = spawner.clone().add(0.5, 0.5, 0.5);
                    world.spawnParticle(Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, center, 30, 0.5, 0.5, 0.5, 0);
                    world.spawnParticle(Particle.OMINOUS_SPAWNING, center, 40, 0.5, 0.8, 0.5, 0.05);
                    Fx.worldSound(center, Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE, 1.5f, 0.7f);
                });
    }

    /** From a configurable Trial Rank on, bosses gain elite affixes (two at twice that rank). */
    private List<Affix> empowerBoss() {
        int threshold = settings().bossAffixRank;
        if (threshold <= 0 || rank < threshold) return List.of();
        List<Affix> pool = new ArrayList<>(List.of(Affix.SWIFT, Affix.BRUTAL, Affix.VAMPIRIC, Affix.MOLTEN,
                Affix.FROSTBITE, Affix.VENOMOUS, Affix.SHIELDED, Affix.GALEBORN));
        pool.removeIf(a -> settings().disabledAffixes.contains(a.name()));
        java.util.Collections.shuffle(pool);
        List<Affix> chosen = new ArrayList<>(pool.subList(0, Math.min(rank >= threshold * 2 ? 2 : 1, pool.size())));
        for (Affix affix : chosen) affix.apply(boss.entity());
        if (!chosen.isEmpty()) {
            boss.entity().getPersistentDataContainer().set(de.t14d3.trickiertrials.util.Keys.AFFIXES, org.bukkit.persistence.PersistentDataType.STRING,
                    String.join(",", chosen.stream().map(Enum::name).toList()));
        }
        return chosen;
    }

    private BossType pickBossType() {
        List<BossType> options = new ArrayList<>();
        for (BossType type : BossType.values()) if (bossAllowed(type) && type != lastBoss) options.add(type);
        if (options.isEmpty()) return lastBoss != null ? lastBoss : BossType.JUGGERNAUT;
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    /** A known spawner close to the party, but not right on top of a player. */
    private Location bossLocation() {
        Collection<Player> players = players();
        Vector center = new Vector();
        for (Player player : players) center.add(player.getLocation().toVector());
        if (!players.isEmpty()) center.multiply(1.0 / players.size());

        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (Location spawner : spawners.keySet()) {
            double distance = spawner.toVector().distance(center);
            double closest = players.stream().mapToDouble(p -> p.getLocation().distance(spawner)).min().orElse(99);
            double score = distance + (closest < 5 ? 50 : 0) + (distance > 30 ? 100 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = spawner;
            }
        }
        if (best != null) return SessionManager.safeSpotAround(best.clone().add(0.5, 0, 0.5), 2);
        Player anchor = players.isEmpty() ? null : players.iterator().next();
        if (anchor == null) return region.getCenter().toLocation(world);
        Location ahead = anchor.getLocation().add(anchor.getLocation().getDirection().setY(0).normalize().multiply(5));
        return SessionManager.safeSpotAround(ahead, 2);
    }

    void tick() {
        if (state == State.ENDED) return;
        ticks += TICK_INTERVAL;
        updatePresence(true);

        if (present.isEmpty()) {
            idleTicks += TICK_INTERVAL;
            if (idleTicks >= settings().idleTimeout * 20) manager.end(this, false);
            return;
        }
        idleTicks = 0;

        for (PlayerRun run : runs.values()) {
            if (run.combo > 0 && ticks - run.lastKillTick > comboWindow()) run.combo = 0;
        }
        tickModifiers();
        if (state == State.WAVE) tickEvent();

        switch (state) {
            case INTERMISSION -> {
                intermissionTicks -= TICK_INTERVAL;
                if (intermissionTicks <= 0) {
                    wave++;
                    beginWave(true);
                }
            }
            case WAVE -> tickReinforcements();
            case BOSS -> {
                if (boss == null) {
                    waveCleared();
                } else if (boss.entity().getHealth() <= 0) {
                    // Killed without a death event reaching us.
                    bossDefeated(null);
                } else if (!boss.entity().isValid() && boss.entity().getLocation().isChunkLoaded()) {
                    // Removed by something else (e.g. another plugin): respawn it where it started.
                    BossType type = boss.type();
                    spawnBoss(type, bossLocation());
                } else {
                    boss.tick();
                }
            }
            default -> {
            }
        }
        if (state != State.ENDED) updateHud();
    }

    private void tickReinforcements() {
        if (!settings().reinforcements) return;
        BoundingBox area = region.clone().expand(8);
        // Mobs that died, despawned or wandered off no longer hold the wave hostage.
        mobs.removeIf(uuid -> {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity == null || !entity.isValid()) {
                manager.unindex(uuid); // despawned or unloaded without dying
                return true;
            }
            return entity.isDead() || !area.contains(entity.getLocation().toVector());
        });
        boolean stalled = ticks - lastProgressTick > STALL_TICKS;
        if (!mobs.isEmpty() && !stalled) {
            emptyTicks = 0;
            return;
        }
        emptyTicks += TICK_INTERVAL;
        if (emptyTicks < settings().reinforcementDelay * 20 && !stalled) return;
        emptyTicks = 0;
        lastProgressTick = ticks;

        // First choice: let the chamber's own trial spawners deliver the next mobs.
        if (settings().wakeSpawners && !stalled) {
            int woken = wakeSpawners(true);
            if (woken > 0 || spawnerBusyNearPlayers()) {
                if (woken > 0 && !awakenedThisWave) {
                    awakenedThisWave = true;
                    for (Player player : players()) Text.send(player, "spawners-awaken");
                }
                return;
            }
        }
        // No spawner can help (none nearby, or the wave is stuck): spawn mobs at the nearest spawner.
        if (settings().fallbackSpawns || !settings().wakeSpawners) spawnReinforcements();
    }

    // ───────────────────────────── Trial spawners ─────────────────────────────

    /** Finds every trial spawner inside the arena (in loaded chunks). */
    private void discoverSpawners() {
        int minX = (int) Math.floor(region.getMinX()) >> 4, maxX = (int) Math.floor(region.getMaxX()) >> 4;
        int minZ = (int) Math.floor(region.getMinZ()) >> 4, maxZ = (int) Math.floor(region.getMaxZ()) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                for (BlockState state : world.getChunkAt(cx, cz).getTileEntities(b -> b.getType() == Material.TRIAL_SPAWNER, false)) {
                    Location location = state.getLocation();
                    if (region.contains(location.toVector().add(new Vector(0.5, 0.5, 0.5)))) spawners.putIfAbsent(location, null);
                }
            }
        }
    }

    private static org.bukkit.block.data.type.TrialSpawner.State spawnerState(Location location) {
        if (!location.isChunkLoaded()) return null;
        return location.getBlock().getBlockData() instanceof org.bukkit.block.data.type.TrialSpawner data ? data.getTrialSpawnerState() : null;
    }

    private boolean nearPlayers(Location location, double radius) {
        for (Player player : players()) {
            if (player.getWorld().equals(location.getWorld()) && player.getLocation().distanceSquared(location) <= radius * radius) return true;
        }
        return false;
    }

    /** True if a spawner near the players is already waiting for them or fighting. */
    private boolean spawnerBusyNearPlayers() {
        for (Location location : spawners.keySet()) {
            org.bukkit.block.data.type.TrialSpawner.State state = spawnerState(location);
            if (state == org.bukkit.block.data.type.TrialSpawner.State.ACTIVE && nearPlayers(location, 24)) return true;
        }
        return false;
    }

    /**
     * Ends the cooldown of trial spawners so vanilla reactivates them: they detect the players again, open
     * their shutters and spawn a fresh set of mobs, exactly like a newly found spawner.
     */
    private int wakeSpawners(boolean nearPlayersOnly) {
        long now = world.getGameTime();
        int woken = 0;
        for (Location location : spawners.keySet()) {
            if (spawnerState(location) != org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN) continue;
            if (nearPlayersOnly && !nearPlayers(location, 24)) continue;
            if (!(location.getBlock().getState() instanceof TrialSpawner spawner)) continue;
            spawner.setCooldownEnd(now);
            spawner.update(true, false);
            Location center = location.clone().add(0.5, 0.5, 0.5);
            world.spawnParticle(ominous ? Particle.TRIAL_SPAWNER_DETECTION_OMINOUS : Particle.TRIAL_SPAWNER_DETECTION, center, 12, 0.4, 0.4, 0.4, 0);
            woken++;
        }
        return woken;
    }

    /** Vanilla spawner loot is paid once per spawner per encounter, so waking spawners can't be farmed. */
    boolean claimSpawnerReward(Location spawner) {
        return rewardedSpawners.add(spawner);
    }

    private void spawnReinforcements() {
        Collection<Player> players = players();
        if (players.isEmpty()) return;
        int remaining = waveTarget - waveKills;
        int count = Math.max(1, Math.min(Math.min(remaining, 8), 2 + players.size()));

        List<Location> candidates = new ArrayList<>();
        for (Location spawner : spawners.keySet()) {
            for (Player player : players) {
                if (player.getLocation().distanceSquared(spawner) < 28 * 28) {
                    candidates.add(spawner);
                    break;
                }
            }
        }
        // Only ever spawn trial chamber mobs: prefer what this chamber's spawners use, else the configured pool.
        Set<EntityType> pool = settings().mobPool;
        List<EntityType> types = new ArrayList<>(new HashSet<>(spawners.values()));
        types.retainAll(pool);
        if (types.isEmpty()) types.addAll(pool);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        MobScaler.Context context = context(players);

        for (int i = 0; i < count; i++) {
            Location origin;
            EntityType type;
            if (!candidates.isEmpty()) {
                Location spawner = candidates.get(random.nextInt(candidates.size()));
                origin = spawner.clone().add(0.5, 0, 0.5);
                type = spawners.get(spawner);
                if (type == null || !pool.contains(type)) type = types.get(random.nextInt(types.size()));
            } else {
                Player player = new ArrayList<>(players).get(random.nextInt(players.size()));
                double angle = random.nextDouble(Math.PI * 2);
                origin = player.getLocation().add(Math.cos(angle) * 6, 0, Math.sin(angle) * 6);
                type = types.get(random.nextInt(types.size()));
            }
            Class<? extends Entity> entityClass = type.getEntityClass();
            if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) continue;
            Location location = SessionManager.safeSpotAround(origin, 2);
            world.spawnParticle(Particle.OMINOUS_SPAWNING, location.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
            world.spawnParticle(Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, location.clone().add(0, 0.2, 0), 8, 0.3, 0.1, 0.3, 0);
            Entity entity = world.spawn(location, entityClass, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
                if (e instanceof LivingEntity living) MobScaler.apply(living, context, settings(), true);
            });
            if (entity instanceof LivingEntity living) registerMob(living);
            Fx.worldSound(location, Sound.BLOCK_TRIAL_SPAWNER_SPAWN_MOB, 1f, 0.9f);
        }
        if (!reinforcedThisWave) {
            reinforcedThisWave = true;
            for (Player player : players) Text.send(player, "reinforcements");
        }
    }

    // ───────────────────────────── Presence & HUD ─────────────────────────────

    void updatePresence(boolean announce) {
        Set<UUID> now = new LinkedHashSet<>();
        BoundingBox area = region.clone().expand(3);
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                if (present.contains(player.getUniqueId()) && player.isDead()) now.add(player.getUniqueId());
                continue;
            }
            if (area.contains(player.getLocation().toVector())) now.add(player.getUniqueId());
        }

        for (UUID uuid : present) {
            if (now.contains(uuid)) continue;
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) hideBars(player);
        }
        for (UUID uuid : now) {
            if (present.contains(uuid)) continue;
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) continue;
            runs.computeIfAbsent(uuid, id -> newRun(id, player.getName()));
            if (settings().bossbar) player.showBossBar(bar);
            if (boss != null) player.showBossBar(boss.bar());
            if (announce) {
                Component message = Text.prefixed("session-join", Text.ph("player", player.getName()), Text.ph("wave", Text.roman(Math.max(1, wave))));
                for (Player other : players()) other.sendMessage(message);
                player.sendMessage(message);
            }
        }
        present.clear();
        present.addAll(now);
    }

    private void hideBars(Player player) {
        player.hideBossBar(bar);
        if (boss != null) player.hideBossBar(boss.bar());
    }

    private void hideBossBar() {
        if (boss == null) return;
        for (Player player : players()) player.hideBossBar(boss.bar());
    }

    private void updateHud() {
        Settings settings = settings();
        if (settings.bossbar) {
            switch (state) {
                case WAVE -> {
                    bar.color(ominous ? BossBar.Color.PURPLE : BossBar.Color.BLUE);
                    bar.progress(clamp((double) waveKills / waveTarget));
                    String label = event == null ? "" : " <dark_gray>·</dark_gray> " + eventLabel()
                            + (event == WaveEvent.BLITZ && eventTicks > 0 ? " <light>" + (eventTicks + 19) / 20 + "s</light>" : "")
                            + (event == WaveEvent.TREASURE && treasure != null ? " <light>" + Math.max(0, eventTicks / 20) + "s</light>" : "");
                    bar.name(Text.msg("bossbar-wave", Text.ph("wave", Text.roman(wave)), Text.ph("final", finalSuffix()),
                            Text.ph("kills", waveKills), Text.ph("target", waveTarget), Text.ph("event", Text.parse(label))));
                }
                case INTERMISSION -> {
                    bar.color(BossBar.Color.GREEN);
                    bar.progress(clamp((double) intermissionTicks / (settings.intermission * 20)));
                    bar.name(Text.msg("bossbar-intermission", Text.ph("seconds", (intermissionTicks + 19) / 20)));
                }
                case BOSS -> {
                    bar.color(BossBar.Color.PURPLE);
                    bar.progress(1f);
                    bar.name(Text.msg("bossbar-boss-wave", Text.ph("wave", Text.roman(wave))));
                }
                default -> {
                }
            }
        }
        if (!settings.actionbar) return;
        String time = Text.duration(elapsed());
        for (Player player : players()) {
            PlayerRun run = runs.get(player.getUniqueId());
            if (run == null) continue;
            Component combo;
            if (run.combo >= 2) {
                double left = 1 - (double) (ticks - run.lastKillTick) / settings.comboWindowTicks;
                combo = Text.msg("actionbar-combo", Text.ph("combo", run.combo),
                        Text.ph("bar", Text.bar(left, 6, Text.color("gold"), Text.color("muted"))));
            } else {
                combo = Text.msg("actionbar-no-combo");
            }
            String waveLabel = state == State.INTERMISSION ? "✔" : Text.roman(wave);
            player.sendActionBar(Text.msg("actionbar", Text.ph("wave", waveLabel), Text.ph("score", Text.number(run.score)),
                    Text.ph("combo", combo), Text.ph("time", time)));
        }
    }

    private static float clamp(double value) {
        return (float) Math.max(0, Math.min(1, value));
    }

    // ───────────────────────────── Mobs ─────────────────────────────

    void recordSpawner(Block spawner, EntityType type, boolean ominousSpawner) {
        spawners.put(spawner.getLocation(), type);
        if (ominousSpawner && !ominous) ominous = true;
    }

    public MobScaler.Context context(Collection<? extends Player> tracked) {
        Collection<? extends Player> basis = tracked.isEmpty() ? players() : tracked;
        double health = (1 + settings().rankHealth * rank) * (has(Modifier.FORTIFIED) ? 1.4 : 1) * (has(Modifier.GOLDEN) ? 1.25 : 1)
                * (event == WaveEvent.SWARM ? 0.6 : 1);
        double damage = (1 + settings().rankDamage * rank) * (has(Modifier.BLOODLUST) ? 1.25 : 1);
        double speed = (has(Modifier.FRENZY) ? 1.2 : 1) * (event == WaveEvent.SWARM ? 1.15 : 1);
        double elite = settings().rankElite * rank + (has(Modifier.ELITE_HUNT) ? 0.25 : 0) + (event == WaveEvent.ELITE_SURGE ? 0.7 : 0);
        return new MobScaler.Context(MobScaler.tierFor(basis), Math.max(1, wave), playerCount(), ominous, health, damage, speed, elite);
    }

    public void registerMob(LivingEntity entity) {
        mobs.add(entity.getUniqueId());
        manager.index(entity, this);
    }

    @Override
    public void registerMinion(LivingEntity minion) {
        MobScaler.apply(minion, context(List.of()), settings(), false);
        registerMob(minion);
    }

    void onMobDeath(LivingEntity entity, Player killer, boolean elite, boolean minion) {
        mobs.remove(entity.getUniqueId());
        if (state == State.ENDED) return;
        if (boss != null && boss.entity().getUniqueId().equals(entity.getUniqueId())) {
            bossDefeated(killer);
            return;
        }
        if (entity.getUniqueId().equals(treasure)) {
            treasure = null;
            treasureCaught(entity.getLocation(), killer);
            return;
        }
        if (has(Modifier.UNSTABLE) && ThreadLocalRandom.current().nextDouble() < 0.25) {
            Location at = entity.getLocation();
            world.spawnParticle(Particle.SMOKE, at.clone().add(0, 0.5, 0), 20, 0.3, 0.3, 0.3, 0.02);
            Fx.worldSound(at, Sound.ENTITY_CREEPER_PRIMED, 1f, 1.3f);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> world.createExplosion(null, at, 1.8f, false, false), 20L);
        }

        if (killer != null) {
            PlayerRun run = runs.computeIfAbsent(killer.getUniqueId(), id -> newRun(id, killer.getName()));
            run.combo = ticks - run.lastKillTick <= comboWindow() ? run.combo + 1 : 1;
            run.lastKillTick = ticks;
            run.bestCombo = Math.max(run.bestCombo, run.combo);
            run.kills++;
            int base = elite ? settings().scoreElite : settings().scoreKill;
            long points = Math.round(base * run.multiplier(settings().comboPerKill, comboMax()) * scoreMultiplier());
            addScore(run, points);
            Fx.sound(killer, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, (float) Math.min(2.0, 0.8 + run.combo * 0.06));
            if (elite) {
                run.elites++;
                Component name = entity.customName() != null ? entity.customName() : Component.translatable(entity.getType());
                Text.send(killer, "elite-slain", Text.ph("name", name), Text.ph("points", points));
            }
            if (run.combo == 5 || run.combo == 10 || run.combo == 15 || (run.combo >= 20 && run.combo % 10 == 0)) {
                if (settings().titles) {
                    killer.showTitle(Title.title(Component.empty(), Text.msg("combo-milestone", Text.ph("combo", run.combo)),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(300))));
                }
                Fx.sound(killer, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1.4f);
            }
        }

        if (state == State.WAVE && !minion) {
            waveKills++;
            lastProgressTick = ticks;
            if (waveKills >= waveTarget) waveCleared();
        }
    }

    private void treasureCaught(Location at, Player killer) {
        ItemStack keys = new ItemStack(org.bukkit.Material.TRIAL_KEY, Math.max(1, (int) Math.round(playerCount() * rewardMultiplier())));
        dropStacks(at, keys);
        world.spawnParticle(Particle.WAX_ON, at.clone().add(0, 1, 0), 60, 0.8, 0.8, 0.8, 0.2);
        Fx.sound(players(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
        Component message = Text.prefixed("treasure-caught", Text.ph("player", killer != null ? killer.getName() : "the chamber"),
                Text.ph("keys", keys.getAmount()));
        for (Player player : players()) player.sendMessage(message);
        if (killer != null) {
            PlayerRun run = runs.computeIfAbsent(killer.getUniqueId(), id -> newRun(id, killer.getName()));
            addScore(run, Math.round(settings().scoreElite * 2 * scoreMultiplier()));
        }
    }

    private PlayerRun newRun(UUID uuid, String name) {
        PlayerRun run = new PlayerRun(uuid, name);
        run.joinWave = Math.max(1, wave);
        return run;
    }

    private void addScore(PlayerRun run, long points) {
        run.score = Math.max(0, run.score + points);
        teamScore = Math.max(0, teamScore + points);
    }

    private void waveCleared() {
        Collection<Player> players = players();
        boolean blitzWon = event == WaveEvent.BLITZ && eventTicks > 0;
        long clearPoints = Math.round(settings().scoreWaveClear * scoreMultiplier() * (blitzWon ? 2 : 1));
        for (Player player : players) {
            PlayerRun run = runs.get(player.getUniqueId());
            if (run != null) addScore(run, clearPoints);
            if (!has(Modifier.FAMINE)) heal(player, settings().waveClearHeal);
            if (blitzWon) {
                for (Settings.Reward reward : settings().blitzRewards) {
                    ItemStack item = reward.roll(has(Modifier.GOLDEN) ? 2 : 1);
                    if (item != null) player.getInventory().addItem(item).values().forEach(left -> world.dropItemNaturally(player.getLocation(), left));
                }
                Text.send(player, "blitz-won");
            }
        }
        if (treasure != null) {
            Entity breeze = plugin.getServer().getEntity(treasure);
            if (breeze != null) breeze.remove();
            treasure = null;
        }
        event = null;
        clearLeftoverMobs();
        if (isFinalWave(wave)) {
            victory();
            return;
        }
        state = State.INTERMISSION;
        intermissionTicks = settings().intermission * 20;
        if (settings().titles) {
            Text.title(Audience.audience(players), "wave-clear-title", "wave-clear-subtitle", 5, 35, 10,
                    Text.ph("points", clearPoints), Text.ph("seconds", settings().intermission));
        }
        Fx.sound(players, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        for (Player player : players) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0);
        }
    }

    /** A cleared wave is really over: the trial mobs still standing vanish (the boss is handled separately). */
    private void clearLeftoverMobs() {
        if (!settings().clearMobsOnWaveClear) return;
        for (UUID uuid : List.copyOf(mobs)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity == null || !entity.isValid() || (boss != null && boss.entity().equals(entity))) continue;
            world.spawnParticle(Particle.POOF, entity.getLocation().add(0, 0.8, 0), 10, 0.3, 0.5, 0.3, 0.02);
            world.spawnParticle(Particle.TRIAL_SPAWNER_DETECTION, entity.getLocation().add(0, 0.2, 0), 4, 0.3, 0.1, 0.3, 0);
            entity.remove();
        }
        for (UUID uuid : mobs) manager.unindex(uuid);
        mobs.clear();
    }

    private static void heal(Player player, double amount) {
        if (amount <= 0) return;
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double limit = max == null ? 20 : max.getValue();
        player.setHealth(Math.min(limit, player.getHealth() + amount));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false, true));
    }

    private void bossDefeated(Player killer) {
        if (boss == null) return;
        TrialBoss defeated = boss;
        hideBossBar();
        defeated.cleanup(false);
        boss = null;

        Location location = defeated.entity().getLocation();
        Collection<Player> players = players();
        double multiplier = playerCount();
        for (Settings.Reward reward : settings().bossRewards) dropStacks(location, reward.roll(multiplier * (has(Modifier.GOLDEN) ? 2 : 1)));
        int experience = (int) (settings().bossExperience * multiplier);
        while (experience > 0) {
            int orb = Math.min(experience, 25);
            experience -= orb;
            world.spawn(location.clone().add(ThreadLocalRandom.current().nextDouble(-1, 1), 0.5, ThreadLocalRandom.current().nextDouble(-1, 1)),
                    ExperienceOrb.class, o -> o.setExperience(orb));
        }
        for (int i = 0; i < 3; i++) {
            Fx.firework(location.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 1, ThreadLocalRandom.current().nextDouble(-2, 2)),
                    Fx.color("gold"), Fx.color("copper"));
        }
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, location.clone().add(0, 1.5, 0), 120, 1, 1.5, 1, 0.4);
        Fx.sound(players, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1f);

        for (Player player : players) {
            PlayerRun run = runs.get(player.getUniqueId());
            if (run == null) continue;
            run.bosses++;
            addScore(run, Math.round(settings().scoreBoss * scoreMultiplier()));
        }
        if (settings().titles) {
            Text.title(Audience.audience(players), "boss-defeated-title", "boss-defeated-subtitle", 5, 50, 15, Text.ph("boss", defeated.name()));
        }
        Component chat = Text.prefixed("boss-defeated-chat", Text.ph("boss", defeated.name()),
                Text.ph("player", killer != null ? killer.getName() : "the chamber"), Text.ph("time", Text.duration(defeated.fightMillis())));
        for (Player player : players) player.sendMessage(chat);
        waveCleared();
    }

    @Override
    public void onBossEnrage(TrialBoss enragedBoss) {
        Component message = Text.prefixed("boss-enrage", Text.ph("boss", enragedBoss.name()));
        for (Player player : players()) player.sendMessage(message);
    }

    void onPlayerDeath(Player player) {
        PlayerRun run = runs.get(player.getUniqueId());
        if (run == null) return;
        run.deaths++;
        run.combo = 0;
        addScore(run, -settings().scoreDeathPenalty);
        Component message = Text.prefixed("player-fallen", Text.ph("player", player.getName()), Text.ph("wave", Text.roman(Math.max(1, wave))),
                Text.ph("penalty", settings().scoreDeathPenalty));
        for (Player other : players()) other.sendMessage(message);
    }

    // ───────────────────────────── Ending ─────────────────────────────

    private void victory() {
        Collection<Player> players = players();
        for (Player player : players) {
            PlayerRun run = runs.get(player.getUniqueId());
            if (run != null) addScore(run, Math.round(settings().scoreVictory * scoreMultiplier()));
            for (Settings.Reward reward : settings().victoryRewards) {
                ItemStack item = reward.roll(rewardMultiplier());
                if (item == null) continue;
                player.getInventory().addItem(item).values().forEach(left -> world.dropItemNaturally(player.getLocation(), left));
            }
        }
        if (settings().titles) {
            Text.title(Audience.audience(players), "victory-title", "victory-subtitle", 10, 80, 20,
                    Text.ph("waves", wave), Text.ph("time", Text.duration(elapsed())));
        }
        Fx.sound(players, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Fx.sound(players, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.6f);
        for (int i = 0; i < 4; i++) {
            final int round = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (Player player : players) {
                    if (!player.isOnline()) continue;
                    Fx.firework(player.getLocation().add(ThreadLocalRandom.current().nextDouble(-3, 3), 1, ThreadLocalRandom.current().nextDouble(-3, 3)),
                            round % 2 == 0 ? Fx.color("gold") : Fx.color("teal"), Fx.color("copper"));
                }
            }, 10L + i * 12L);
        }
        if (settings().broadcastVictories) {
            String names = String.join(", ", players.stream().map(Player::getName).toList());
            plugin.getServer().broadcast(Text.prefixed("victory-broadcast", Text.ph("players", names),
                    Text.ph("time", Text.duration(elapsed())), Text.ph("score", Text.number(teamScore))));
        }
        manager.end(this, true);
    }

    /** Called by the manager. Hides bars, removes the boss, sends summaries and records stats. */
    void finish(boolean victory, boolean announce) {
        finish(victory, announce, true);
    }

    /** {@code penalize}: whether a failed run may cost rank progress (not for restarts or admin-ended trials). */
    void finish(boolean victory, boolean announce, boolean penalize) {
        if (state == State.ENDED) return;
        state = State.ENDED;
        for (UUID uuid : runs.keySet()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) hideBars(player);
        }
        if (boss != null) {
            boss.cleanup(true);
            boss = null;
        }

        int reached = Math.max(1, wave);
        PlayerRun mvp = runs.values().stream().max(Comparator.comparingLong(r -> r.score)).orElse(null);
        for (PlayerRun run : runs.values()) {
            List<String> records = plugin.stats().record(run, reached, victory,
                    settings().progressionEnabled ? settings().rankRules() : null, rank, penalize);
            Player player = plugin.getServer().getPlayer(run.uuid);
            if (player == null || !announce) continue;
            if (!victory) Text.send(player, "abandoned", Text.ph("wave", Text.roman(reached)));
            TagResolver[] resolvers = {
                    Text.ph("result", Text.msg(victory ? "summary-victory" : "summary-abandoned")),
                    Text.ph("wave", Text.roman(reached)),
                    Text.ph("time", Text.duration(elapsed())),
                    Text.ph("team_score", Text.number(teamScore)),
                    Text.ph("score", Text.number(run.score)),
                    Text.ph("kills", run.kills),
                    Text.ph("elites", run.elites),
                    Text.ph("bosses", run.bosses),
                    Text.ph("best_combo", run.bestCombo),
                    Text.ph("deaths", run.deaths),
                    Text.ph("mvp", mvp == null ? "-" : mvp.name),
                    Text.ph("rank", rankLabel()), Text.ph("stars", RankBadge.component(rank)),
                    Text.ph("modifiers", Text.parse(modifierList()))
            };
            for (String line : plugin.getConfig().getStringList("messages.summary")) player.sendMessage(Text.parse(line, resolvers));
            for (String record : records) {
                String[] part = record.split(":");
                if (record.startsWith("PROGRESS:")) {
                    Text.send(player, "rank-progress", Text.ph("have", part[1]), Text.ph("need", part[2]),
                            Text.ph("stars", RankBadge.component(plugin.stats().rank(run.uuid) + 1)));
                } else if (record.startsWith("NOPROGRESS:")) {
                    Text.send(player, "rank-no-progress-" + part[1], Text.ph("have", part[2]), Text.ph("need", part[3]),
                            Text.ph("max_deaths", settings().rankMaxDeaths), Text.ph("join_wave", settings().rankJoinByWave));
                } else if (record.startsWith("LOST:")) {
                    Text.send(player, "rank-progress-lost", Text.ph("have", part[1]), Text.ph("need", part[2]));
                } else if (record.startsWith("RANK:")) {
                    int newRank = Integer.parseInt(record.substring(5));
                    Text.send(player, "rank-up", Text.ph("rank", Text.roman(newRank)), Text.ph("stars", RankBadge.component(newRank)));
                    if (settings().titles) Text.title(player, "rank-up-title", "rank-up-subtitle", 10, 60, 20, Text.ph("rank", Text.roman(newRank)), Text.ph("stars", RankBadge.component(newRank)));
                } else {
                    Text.send(player, "new-record", Text.ph("what", record));
                }
            }
        }
        plugin.stats().saveAsync();
    }

    static void dropStacks(Location location, ItemStack item) {
        if (item == null) return;
        int remaining = item.getAmount();
        int max = Math.max(1, item.getMaxStackSize());
        while (remaining > 0) {
            int amount = Math.min(max, remaining);
            remaining -= amount;
            ItemStack stack = item.clone();
            stack.setAmount(amount);
            location.getWorld().dropItemNaturally(location, stack);
        }
    }
}
