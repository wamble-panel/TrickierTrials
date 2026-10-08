package de.t14d3.trickiertrials.session;

import de.t14d3.trickiertrials.Settings;
import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.boss.BossHost;
import de.t14d3.trickiertrials.boss.BossType;
import de.t14d3.trickiertrials.boss.TrialBoss;
import de.t14d3.trickiertrials.mob.MobScaler;
import de.t14d3.trickiertrials.util.Fx;
import de.t14d3.trickiertrials.util.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
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
    private long ticks;
    private long lastProgressTick;
    private long teamScore;
    private TrialBoss boss;
    private BossType lastBoss;

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
        for (BossType type : BossType.values()) if (settings().bossEnabled(type.id())) return true;
        return false;
    }

    private String finalSuffix() {
        return settings().finalWave > 0 ? "/" + Text.roman(settings().finalWave) : "";
    }

    // ───────────────────────────── Lifecycle ─────────────────────────────

    void start() {
        updatePresence(false);
        wave = 1;
        Collection<Player> players = players();
        if (settings().titles) {
            Text.title(Audience.audience(players), ominous ? "session-start-title-ominous" : "session-start-title", "session-start-subtitle", 10, 50, 15,
                    Text.ph("waves", settings().finalWave > 0 ? String.valueOf(settings().finalWave) : "∞"),
                    Text.ph("players", players.size()));
        }
        Fx.sound(players, ominous ? Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE : Sound.BLOCK_TRIAL_SPAWNER_DETECT_PLAYER, 1f, 0.8f);
        beginWave(false);
    }

    private void beginWave(boolean announce) {
        reinforcedThisWave = false;
        emptyTicks = 0;
        lastProgressTick = ticks;
        Collection<Player> players = players();
        if (isBossWave(wave)) {
            state = State.BOSS;
            spawnBoss(pickBossType(), bossLocation());
            return;
        }
        state = State.WAVE;
        waveKills = 0;
        waveTarget = settings().killsBase + settings().killsPerExtraPlayer * (playerCount() - 1) + settings().killsPerWave * (wave - 1);
        if (announce && settings().titles) {
            Text.title(Audience.audience(players), "wave-start-title", "wave-start-subtitle", 5, 35, 10,
                    Text.ph("wave", Text.roman(wave)), Text.ph("target", waveTarget));
        }
        if (announce) Fx.sound(players, Sound.EVENT_RAID_HORN, 0.6f, 1.2f);
    }

    public void spawnBoss(BossType type, Location location) {
        if (boss != null) {
            boss.cleanup(true);
            hideBossBar();
        }
        state = State.BOSS;
        lastBoss = type;
        boss = TrialBoss.spawn(this, type, location, playerCount(), ominous, isFinalWave(wave));
        manager.index(boss.entity(), this);
        Collection<Player> players = players();
        for (Player player : players) player.showBossBar(boss.bar());
        if (settings().titles) {
            Text.title(Audience.audience(players), "boss-incoming-title", "boss-incoming-subtitle", 5, 50, 15, Text.ph("boss", boss.name()));
        }
        Fx.sound(players, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.9f);
    }

    private BossType pickBossType() {
        List<BossType> options = new ArrayList<>();
        for (BossType type : BossType.values()) if (settings().bossEnabled(type.id()) && type != lastBoss) options.add(type);
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
            if (run.combo > 0 && ticks - run.lastKillTick > settings().comboWindowTicks) run.combo = 0;
        }

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
            return entity == null || entity.isDead() || !entity.isValid() || !area.contains(entity.getLocation().toVector());
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
        spawnReinforcements();
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
        List<EntityType> types = new ArrayList<>(new HashSet<>(spawners.values()));
        if (types.isEmpty()) types.add(EntityType.ZOMBIE);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        MobScaler.Context context = new MobScaler.Context(MobScaler.tierFor(players), wave, playerCount(), ominous);

        for (int i = 0; i < count; i++) {
            Location origin;
            EntityType type;
            if (!candidates.isEmpty()) {
                Location spawner = candidates.get(random.nextInt(candidates.size()));
                origin = spawner.clone().add(0.5, 0, 0.5);
                type = spawners.getOrDefault(spawner, types.get(random.nextInt(types.size())));
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
            runs.computeIfAbsent(uuid, id -> new PlayerRun(id, player.getName()));
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
                    bar.name(Text.msg("bossbar-wave", Text.ph("wave", Text.roman(wave)), Text.ph("final", finalSuffix()),
                            Text.ph("kills", waveKills), Text.ph("target", waveTarget)));
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
        return new MobScaler.Context(MobScaler.tierFor(basis), Math.max(1, wave), playerCount(), ominous);
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

        if (killer != null) {
            PlayerRun run = runs.computeIfAbsent(killer.getUniqueId(), id -> new PlayerRun(id, killer.getName()));
            run.combo = ticks - run.lastKillTick <= settings().comboWindowTicks ? run.combo + 1 : 1;
            run.lastKillTick = ticks;
            run.bestCombo = Math.max(run.bestCombo, run.combo);
            run.kills++;
            int base = elite ? settings().scoreElite : settings().scoreKill;
            long points = Math.round(base * run.multiplier(settings().comboPerKill, settings().comboMax));
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

    private void addScore(PlayerRun run, long points) {
        run.score = Math.max(0, run.score + points);
        teamScore = Math.max(0, teamScore + points);
    }

    private void waveCleared() {
        Collection<Player> players = players();
        for (Player player : players) {
            PlayerRun run = runs.get(player.getUniqueId());
            if (run != null) addScore(run, settings().scoreWaveClear);
            heal(player, settings().waveClearHeal);
        }
        if (isFinalWave(wave)) {
            victory();
            return;
        }
        state = State.INTERMISSION;
        intermissionTicks = settings().intermission * 20;
        if (settings().titles) {
            Text.title(Audience.audience(players), "wave-clear-title", "wave-clear-subtitle", 5, 35, 10,
                    Text.ph("points", settings().scoreWaveClear), Text.ph("seconds", settings().intermission));
        }
        Fx.sound(players, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        for (Player player : players) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0);
        }
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
        for (Settings.Reward reward : settings().bossRewards) dropStacks(location, reward.roll(multiplier));
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
            addScore(run, settings().scoreBoss);
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
            if (run != null) addScore(run, settings().scoreVictory);
            for (Settings.Reward reward : settings().victoryRewards) {
                ItemStack item = reward.roll(1);
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
            List<String> records = plugin.stats().record(run, reached, victory);
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
                    Text.ph("mvp", mvp == null ? "-" : mvp.name)
            };
            for (String line : plugin.getConfig().getStringList("messages.summary")) player.sendMessage(Text.parse(line, resolvers));
            for (String record : records) Text.send(player, "new-record", Text.ph("what", record));
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
