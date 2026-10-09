package de.t14d3.trickiertrials.guard;

import de.t14d3.trickiertrials.Settings;
import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.boss.BossHost;
import de.t14d3.trickiertrials.boss.BossType;
import de.t14d3.trickiertrials.boss.TrialBoss;
import de.t14d3.trickiertrials.mob.MobScaler;
import de.t14d3.trickiertrials.util.Fx;
import de.t14d3.trickiertrials.util.Keys;
import de.t14d3.trickiertrials.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A Chamber Warden: a boss that patrols between waypoints (e.g. the corridor in front of the chambers).
 * While it lives, every trial spawner and vault within the guard radius of its post is sealed.
 * Killing it opens them for a while; afterwards it returns on its own.
 */
public final class Warden implements BossHost {

    private final TrickierTrials plugin;
    private final String name;
    private final Location post;
    private final List<Location> waypoints = new ArrayList<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> barViewers = new HashSet<>();

    private TrialBoss boss;
    private long openUntil;
    private int waypoint;
    private int direction = 1;
    private int pathCooldown;
    private int calmTicks;

    Warden(TrickierTrials plugin, String name, Location post) {
        this.plugin = plugin;
        this.name = name;
        this.post = post.clone();
    }

    private Settings settings() {
        return plugin.settings();
    }

    public String name() {
        return name;
    }

    public Location post() {
        return post.clone();
    }

    public List<Location> waypoints() {
        return waypoints;
    }

    public long openUntil() {
        return openUntil;
    }

    void openUntil(long until) {
        this.openUntil = until;
    }

    public boolean isOpen() {
        return System.currentTimeMillis() < openUntil;
    }

    public boolean isAlive() {
        return boss != null && boss.isAlive();
    }

    public boolean isBoss(LivingEntity entity) {
        return boss != null && boss.entity().getUniqueId().equals(entity.getUniqueId());
    }

    public TrialBoss boss() {
        return boss;
    }

    /** True if the location is guarded by this warden and the warden has not been defeated. */
    public boolean seals(Location location) {
        if (isOpen() || location.getWorld() == null || !location.getWorld().equals(post.getWorld())) return false;
        double radius = settings().guardRadius;
        return location.distanceSquared(post) <= radius * radius;
    }

    public Component displayName() {
        return Text.parse(settings().guardName);
    }

    // ───────────────────────────── BossHost ─────────────────────────────

    @Override
    public TrickierTrials plugin() {
        return plugin;
    }

    @Override
    public Collection<Player> players() {
        List<Player> list = new ArrayList<>();
        if (boss == null) return list;
        Location at = boss.entity().getLocation();
        double range = settings().guardAggroRange * 2;
        for (Player player : at.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(at) <= range * range) list.add(player);
        }
        return list;
    }

    @Override
    public void registerMinion(LivingEntity minion) {
        MobScaler.apply(minion, new MobScaler.Context(MobScaler.Tier.DEFAULT, 1, 1, false), settings(), false);
    }

    @Override
    public void onBossEnrage(TrialBoss enraged) {
        Component message = Text.prefixed("boss-enrage", Text.ph("boss", enraged.name()));
        for (Player player : players()) player.sendMessage(message);
    }

    // ───────────────────────────── Lifecycle ─────────────────────────────

    void tick() {
        if (isOpen()) return;
        if (boss == null || !boss.entity().isValid()) {
            if (boss != null) despawn(); // unloaded or removed: start fresh
            Location spawn = waypoints.isEmpty() ? post : waypoints.getFirst();
            if (spawn.isChunkLoaded()) spawn(spawn);
            return;
        }

        boss.tick();
        updateBars();
        Mob mob = boss.entity() instanceof Mob m ? m : null;
        if (mob == null) return;

        Collection<Player> threats = boss.playersInAggroRange();
        if (!threats.isEmpty()) {
            calmTicks = 0;
            return;
        }

        // Nobody around: drop the chase, heal up slowly and walk the patrol route.
        if (mob.getTarget() != null) mob.setTarget(null);
        calmTicks += 10;
        if (calmTicks > 200) {
            AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) mob.setHealth(Math.min(max.getValue(), mob.getHealth() + max.getValue() * 0.02));
        }
        if (waypoints.isEmpty()) {
            if (mob.getLocation().distanceSquared(post) > 4 && --pathCooldown <= 0) {
                mob.getPathfinder().moveTo(post, 0.9);
                pathCooldown = 4;
            }
            return;
        }
        Location target = waypoints.get(waypoint);
        if (mob.getLocation().distanceSquared(target) < 2.5) {
            if (waypoints.size() > 1) {
                if (waypoint + direction < 0 || waypoint + direction >= waypoints.size()) direction = -direction;
                waypoint += direction;
            }
            target = waypoints.get(waypoint);
            pathCooldown = 0;
        }
        if (--pathCooldown <= 0 || !mob.getPathfinder().hasPath()) {
            mob.getPathfinder().moveTo(target, 0.9);
            pathCooldown = 6;
        }
    }

    void spawn(Location location) {
        BossType type = BossType.byId(settings().guardBoss);
        if (type == null) type = BossType.JUGGERNAUT;
        double health = Math.min(1024, settings().guardHealth);
        boss = TrialBoss.spawnCustom(this, type, location, displayName(), health, settings().guardDamage, false);
        boss.entity().getPersistentDataContainer().set(Keys.GUARD, PersistentDataType.STRING, name);
        boss.aggroRange(settings().guardAggroRange);
        boss.leash(post, settings().guardLeashRange);
        AttributeInstance follow = boss.entity().getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null) follow.setBaseValue(settings().guardAggroRange + 4);
        participants.clear();
        waypoint = 0;
        direction = 1;
        double radius = settings().guardRadius;
        Component message = Text.prefixed("warden-returns", Text.ph("warden", displayName()));
        for (Player player : post.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(post) <= radius * radius) player.sendMessage(message);
        }
    }

    void despawn() {
        hideBars();
        if (boss != null) boss.cleanup(true);
        boss = null;
    }

    void addDamage(Player player, double amount) {
        if (boss != null) boss.addDamage(player.getUniqueId(), amount);
    }

    /** Called when the warden is killed. */
    void onDeath(Player killer) {
        if (boss == null) return;
        Location at = boss.entity().getLocation();
        hideBars();
        boss.cleanup(false);
        // Only players who did their share of the damage earn the reward - standing nearby is not enough.
        java.util.Map<UUID, Double> damage = boss.damageBy();
        participants.clear();
        participants.addAll(de.t14d3.trickiertrials.util.Contribution.contributors(damage, damage.size(), settings().minContribution));
        boss = null;
        List<String> names = new ArrayList<>();
        for (UUID uuid : participants) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) continue;
            names.add(player.getName());
            for (Settings.Reward reward : settings().guardRewards) {
                ItemStack item = reward.roll(1);
                if (item != null) player.getInventory().addItem(item).values().forEach(left -> at.getWorld().dropItemNaturally(player.getLocation(), left));
            }
            Fx.sound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        participants.clear();

        openUntil = System.currentTimeMillis() + settings().guardUnsealSeconds * 1000L;
        for (int i = 0; i < 3; i++) {
            Fx.firework(at.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 1, ThreadLocalRandom.current().nextDouble(-2, 2)),
                    Fx.color("gold"), Fx.color("copper"));
        }
        Component message = Text.prefixed("warden-slain", Text.ph("warden", displayName()),
                Text.ph("players", names.isEmpty() ? "the chamber" : String.join(", ", names)),
                Text.ph("time", Text.duration(settings().guardUnsealSeconds * 1000L)));
        double radius = settings().guardRadius + 32;
        for (Player player : at.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(post) <= radius * radius) player.sendMessage(message);
        }
    }

    private void updateBars() {
        Location at = boss.entity().getLocation();
        Set<UUID> now = new HashSet<>();
        for (Player player : at.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(at) <= 32 * 32) now.add(player.getUniqueId());
        }
        for (UUID uuid : barViewers) {
            if (now.contains(uuid)) continue;
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) player.hideBossBar(boss.bar());
        }
        for (UUID uuid : now) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && !barViewers.contains(uuid)) player.showBossBar(boss.bar());
        }
        barViewers.clear();
        barViewers.addAll(now);
    }

    private void hideBars() {
        if (boss == null) return;
        for (UUID uuid : barViewers) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) player.hideBossBar(boss.bar());
        }
        barViewers.clear();
    }
}
