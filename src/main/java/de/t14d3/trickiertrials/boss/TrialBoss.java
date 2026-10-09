package de.t14d3.trickiertrials.boss;

import de.t14d3.trickiertrials.Settings;
import de.t14d3.trickiertrials.mob.Affix;
import de.t14d3.trickiertrials.mob.MobScaler;
import de.t14d3.trickiertrials.util.Fx;
import de.t14d3.trickiertrials.util.Keys;
import de.t14d3.trickiertrials.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BreezeWindCharge;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A boss with telegraphed abilities, minion phases and an enrage phase - inspired by MMO raid bosses.
 * {@link #tick()} is called every 10 server ticks by the owning encounter.
 */
public final class TrialBoss {

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final BossHost host;
    private final BossType type;
    private final LivingEntity entity;
    private final Component name;
    private Location home;
    private double leashRange = 32;
    private double aggroRange = 40;
    private final double damageMultiplier;
    private final long spawnedAt = System.currentTimeMillis();
    private final BossBar bar;
    private final Set<Location> webs = new HashSet<>();

    private final java.util.Map<java.util.UUID, Double> damageBy = new java.util.HashMap<>();
    private boolean enraged;
    private boolean firstMinions;
    private boolean secondMinions;
    private int primaryCooldown = 8;
    private int secondaryCooldown = 14;

    private TrialBoss(BossHost host, BossType type, LivingEntity entity, Component name, Location home, double damageMultiplier, boolean ominous) {
        this.host = host;
        this.type = type;
        this.entity = entity;
        this.name = name;
        this.home = home.clone();
        this.damageMultiplier = damageMultiplier;
        this.bar = BossBar.bossBar(Component.empty(), 1f, ominous ? BossBar.Color.PURPLE : BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        updateBar();
    }

    public static TrialBoss spawn(BossHost host, BossType type, Location location, int players, boolean ominous, boolean finalBoss) {
        return spawn(host, type, location, players, ominous, finalBoss, 1);
    }

    /** {@code strength} multiplies health on top of party size (Trial Rank, modifiers). */
    public static TrialBoss spawn(BossHost host, BossType type, Location location, int players, boolean ominous, boolean finalBoss, double strength) {
        Settings settings = host.plugin().settings();
        double multiplier = (1 + settings.bossHealthPerExtraPlayer * Math.max(0, players - 1))
                * (ominous ? settings.bossOminousMultiplier : 1) * (finalBoss ? settings.bossFinalMultiplier : 1);
        double health = settings.bossHealth(type.id(), type.defaultHealth()) * multiplier * strength;
        double damage = settings.bossDamage(type.id()) * (ominous ? settings.bossOminousMultiplier : 1) * (finalBoss ? 1.2 : 1);
        Component name = Text.parse(settings.bossName(type.id(), type.defaultName()));
        return spawnCustom(host, type, location, name, health, damage, ominous);
    }

    /** Spawns a boss with explicit stats (used for the Chamber Warden). */
    public static TrialBoss spawnCustom(BossHost host, BossType type, Location location, Component name, double health, double damage, boolean ominous) {
        // Health caps at 1024: for huge groups, the rest of the bulk becomes damage resistance.
        double wanted = health;
        health = Math.min(1024, wanted);
        double damageTaken = health / wanted;
        World world = location.getWorld();
        world.strikeLightningEffect(location);
        world.spawnParticle(Particle.TRIAL_OMEN, location.clone().add(0, 1, 0), 80, 1.2, 1.2, 1.2, 0.05);
        world.spawnParticle(Particle.SONIC_BOOM, location.clone().add(0, 1, 0), 1);
        Fx.worldSound(location, Sound.ENTITY_WITHER_SPAWN, 1.2f, 1.1f);

        double finalHealth = health;
        LivingEntity entity = world.spawn(location, type.entityClass(), CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
            setBase(e, Attribute.MAX_HEALTH, finalHealth);
            e.setHealth(finalHealth);
            Affix.multiply(e, Attribute.ATTACK_DAMAGE, damage);
            setBase(e, Attribute.SCALE, type.scale());
            setBase(e, Attribute.KNOCKBACK_RESISTANCE, 0.8);
            setBase(e, Attribute.FOLLOW_RANGE, 48);
            e.customName(name);
            e.setCustomNameVisible(true);
            e.setRemoveWhenFarAway(false);
            e.setPersistent(true);
            e.setGlowing(true);
            e.getPersistentDataContainer().set(Keys.BOSS, PersistentDataType.STRING, type.id());
            if (damageTaken < 1) e.getPersistentDataContainer().set(Keys.DAMAGE_TAKEN, PersistentDataType.DOUBLE, damageTaken);
            Keys.markTrialMob(e);
            equip(e, type);
        });
        // Vanilla may add jockeys - a boss rides alone.
        entity.getPassengers().forEach(Entity::remove);
        if (entity.getVehicle() != null) entity.getVehicle().remove();
        return new TrialBoss(host, type, entity, name, location, damage, ominous);
    }

    private static void setBase(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    private static void equip(LivingEntity entity, BossType type) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;
        switch (type) {
            case JUGGERNAUT -> {
                equipment.setItem(EquipmentSlot.HEAD, new ItemStack(Material.COPPER_HELMET));
                equipment.setItem(EquipmentSlot.CHEST, new ItemStack(Material.COPPER_CHESTPLATE));
                equipment.setItem(EquipmentSlot.LEGS, new ItemStack(Material.COPPER_LEGGINGS));
                equipment.setItem(EquipmentSlot.FEET, new ItemStack(Material.COPPER_BOOTS));
                equipment.setItemInMainHand(new ItemStack(Material.MACE));
            }
            case RIMEBORN, BOG_SOVEREIGN -> {
                equipment.setItemInMainHand(new ItemStack(Material.BOW));
                equipment.setItem(EquipmentSlot.HEAD, new ItemStack(type == BossType.RIMEBORN ? Material.DIAMOND_HELMET : Material.CHAINMAIL_HELMET));
            }
            default -> {
            }
        }
        for (EquipmentSlot slot : ARMOR) equipment.setDropChance(slot, 0f);
        equipment.setDropChance(EquipmentSlot.HAND, 0f);
        if (entity instanceof Zombie zombie) {
            zombie.setAdult();
            zombie.setShouldBurnInDay(false);
        }
        if (entity instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
    }

    // ───────────────────────────── Accessors ─────────────────────────────

    public BossType type() {
        return type;
    }

    public LivingEntity entity() {
        return entity;
    }

    public Component name() {
        return name;
    }

    public BossBar bar() {
        return bar;
    }

    public boolean isAlive() {
        return entity.isValid() && !entity.isDead();
    }

    public boolean enraged() {
        return enraged;
    }

    public long fightMillis() {
        return System.currentTimeMillis() - spawnedAt;
    }

    public void addDamage(java.util.UUID player, double amount) {
        damageBy.merge(player, amount, Double::sum);
    }

    /** Damage dealt to this boss by each player. */
    public java.util.Map<java.util.UUID, Double> damageBy() {
        return damageBy;
    }

    /** Pulls the boss back to {@code home} whenever it gets further away than {@code range}. */
    public void leash(Location home, double range) {
        this.home = home.clone();
        this.leashRange = range;
    }

    /** Only players within this range are targeted and hit by abilities. */
    public void aggroRange(double range) {
        this.aggroRange = range;
    }

    public Collection<Player> playersInAggroRange() {
        return nearbyPlayers(aggroRange);
    }

    public boolean isWeb(Location location) {
        return webs.contains(location);
    }

    private double maxHealth() {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 1 : max.getValue();
    }

    public void updateBar() {
        double max = maxHealth();
        double health = Math.max(0, entity.getHealth());
        bar.progress((float) Math.max(0, Math.min(1, health / max)));
        bar.name(Text.msg("bossbar-boss",
                Text.ph("boss", name),
                Text.ph("health", Text.number(Math.round(health))),
                Text.ph("max", Text.number(Math.round(max))),
                Text.ph("enraged", enraged ? Text.msg("bossbar-enraged") : Component.empty())));
    }

    // ───────────────────────────── Behaviour ─────────────────────────────

    public void tick() {
        if (!isAlive()) return;
        Collection<Player> players = nearbyPlayers(aggroRange);
        updateBar();

        // Leash: never let the boss be dragged out of the arena.
        if (entity.getLocation().distanceSquared(home) > leashRange * leashRange || entity.getLocation().getY() < home.getY() - 12) {
            entity.getWorld().spawnParticle(Particle.REVERSE_PORTAL, entity.getLocation().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.1);
            entity.teleport(home);
            Fx.worldSound(home, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
        }
        if (players.isEmpty()) return;

        if (entity instanceof Mob mob && (mob.getTarget() == null || !(mob.getTarget() instanceof Player target) || !players.contains(target))) {
            players.stream().min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(entity.getLocation()))).ifPresent(mob::setTarget);
        }

        double fraction = entity.getHealth() / maxHealth();
        if (!firstMinions && fraction <= 0.66) {
            firstMinions = true;
            summonMinions(players.size());
        }
        if (!secondMinions && fraction <= 0.33) {
            secondMinions = true;
            summonMinions(players.size() + 1);
        }
        if (!enraged && fraction <= host.plugin().settings().bossEnrageThreshold) enrage();

        try {
            if (--primaryCooldown <= 0) {
                primaryCooldown = enraged ? 6 : 10;
                primary(players);
            }
            if (--secondaryCooldown <= 0) {
                secondaryCooldown = enraged ? 11 : 17;
                secondary(players);
            }
        } catch (RuntimeException e) {
            host.plugin().getLogger().warning("Boss ability of " + type.id() + " failed: " + e);
        }

        // Ambient aura
        Location aura = entity.getLocation().add(0, 0.2, 0);
        Fx.dustRing(aura, enraged ? Fx.color("danger") : Fx.color("ominous"), 1.2 * type.scale(), 14, 1.2f);
    }

    private Collection<Player> nearbyPlayers(double radius) {
        List<Player> list = new ArrayList<>();
        for (Player player : host.players()) {
            if (player.getWorld().equals(entity.getWorld()) && player.getLocation().distanceSquared(entity.getLocation()) <= radius * radius && !player.isDead()) {
                list.add(player);
            }
        }
        return list;
    }

    private Player randomPlayer(Collection<Player> players) {
        List<Player> list = new ArrayList<>(players);
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private void enrage() {
        enraged = true;
        Affix.multiply(entity, Attribute.MOVEMENT_SPEED, 1.25);
        Affix.multiply(entity, Attribute.ATTACK_DAMAGE, 1.3);
        Affix.multiply(entity, Attribute.SCALE, 1.12);
        entity.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, entity.getEyeLocation(), 12, 0.6, 0.6, 0.6, 0);
        Fx.worldSound(entity.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.8f);
        bar.color(BossBar.Color.RED);
        host.onBossEnrage(this);
    }

    private void summonMinions(int count) {
        count = Math.min(count, 6); // keep huge groups from flooding the chamber
        Location base = entity.getLocation();
        Fx.worldSound(base, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.2f, 0.9f);
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            Location location = safeNear(base.clone().add(Math.cos(angle) * 2.5, 0, Math.sin(angle) * 2.5), base);
            location.getWorld().spawnParticle(Particle.OMINOUS_SPAWNING, location.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
            LivingEntity minion = location.getWorld().spawn(location, type.minionClass(), CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
                e.getPersistentDataContainer().set(Keys.MINION, PersistentDataType.BYTE, (byte) 1);
                if (type == BossType.BROOD_MOTHER) MobScaler.scaleHealth(e, 1.5);
            });
            host.registerMinion(minion);
        }
    }

    /** Applies on-hit effects when the boss itself hits a player. */
    public void onMelee(Player victim) {
        switch (type) {
            case BROOD_MOTHER -> victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, enraged ? 1 : 0));
            case JUGGERNAUT -> {
                Vector push = victim.getLocation().toVector().subtract(entity.getLocation().toVector()).setY(0);
                if (push.lengthSquared() > 0.01) victim.setVelocity(push.normalize().multiply(1.2).setY(0.45));
            }
            case RIMEBORN -> victim.setFreezeTicks(Math.min(victim.getMaxFreezeTicks() + 60, victim.getFreezeTicks() + 60));
            default -> {
            }
        }
    }

    private void primary(Collection<Player> players) {
        switch (type) {
            case TEMPEST -> windVolley(players);
            case BOG_SOVEREIGN -> toxicRain(randomPlayer(players));
            case JUGGERNAUT -> seismicSlam();
            case BROOD_MOTHER -> webSnare(players);
            case RIMEBORN -> frostNova();
        }
    }

    private void secondary(Collection<Player> players) {
        switch (type) {
            case TEMPEST -> galeBurst();
            case BOG_SOVEREIGN -> sporeCloud();
            case JUGGERNAUT -> charge(players);
            case BROOD_MOTHER -> summonMinions(2);
            case RIMEBORN -> blink(randomPlayer(players));
        }
    }

    // ── Tempest ──

    private void windVolley(Collection<Player> players) {
        List<Player> targets = new ArrayList<>(players);
        int shots = Math.min(targets.size(), enraged ? 4 : 2);
        for (int i = 0; i < shots; i++) {
            Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
            Vector direction = target.getEyeLocation().toVector().subtract(entity.getEyeLocation().toVector()).normalize().multiply(1.1);
            entity.launchProjectile(BreezeWindCharge.class, direction);
        }
        Fx.worldSound(entity.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 1.2f, 0.8f);
    }

    private void galeBurst() {
        Location center = entity.getLocation();
        telegraph(center, 9, Fx.color("sky"));
        host.plugin().getServer().getScheduler().runTaskLater(host.plugin(), () -> {
            if (!isAlive()) return;
            Location now = entity.getLocation();
            now.getWorld().spawnParticle(Particle.GUST_EMITTER_LARGE, now.clone().add(0, 0.5, 0), 1);
            Fx.worldSound(now, Sound.ENTITY_BREEZE_WIND_BURST, 1.5f, 0.6f);
            for (Player player : nearbyPlayers(9)) {
                Vector push = player.getLocation().toVector().subtract(now.toVector()).setY(0);
                if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
                player.setVelocity(push.normalize().multiply(1.6).setY(0.9));
                player.damage(3 * damageMultiplier, entity);
            }
        }, 20L);
    }

    // ── Bog Sovereign ──

    private void toxicRain(Player target) {
        Location center = target.getLocation();
        telegraph(center, 3.5, Fx.color("success"));
        Fx.worldSound(entity.getLocation(), Sound.ENTITY_BOGGED_AMBIENT, 1.5f, 0.6f);
        host.plugin().getServer().getScheduler().runTaskLater(host.plugin(), () -> {
            if (!isAlive()) return;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int arrows = enraged ? 16 : 10;
            for (int i = 0; i < arrows; i++) {
                Location from = center.clone().add(random.nextDouble(-3, 3), 9, random.nextDouble(-3, 3));
                if (!from.getBlock().isPassable()) from = center.clone().add(0, 3, 0);
                Arrow arrow = center.getWorld().spawn(from, Arrow.class, a -> {
                    a.setShooter(entity);
                    a.setVelocity(new Vector(0, -1.6, 0));
                    a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    a.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 60, 0), true);
                    a.setDamage(2.5 * damageMultiplier);
                });
                host.plugin().getServer().getScheduler().runTaskLater(host.plugin(), arrow::remove, 100L);
            }
            Fx.worldSound(center, Sound.ENTITY_ARROW_SHOOT, 1.5f, 0.6f);
        }, 20L);
    }

    private void sporeCloud() {
        Location at = entity.getLocation();
        at.getWorld().spawn(at, AreaEffectCloud.class, cloud -> {
            cloud.setRadius(enraged ? 5f : 3.5f);
            cloud.setDuration(120);
            cloud.setRadiusPerTick(0.01f);
            cloud.setColor(Fx.color("success"));
            cloud.setSource(entity);
            cloud.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 1), true);
        });
        Fx.worldSound(at, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.6f);
    }

    // ── Juggernaut ──

    private void seismicSlam() {
        if (nearbyPlayers(7).isEmpty()) return;
        entity.setVelocity(new Vector(0, 0.85, 0));
        telegraph(entity.getLocation(), 6, Fx.color("copper"));
        host.plugin().getServer().getScheduler().runTaskLater(host.plugin(), () -> {
            if (!isAlive()) return;
            Location now = entity.getLocation();
            now.getWorld().spawnParticle(Particle.EXPLOSION, now, 6, 2, 0.2, 2, 0);
            Fx.dustRing(now, Fx.color("copper"), 3, 24, 2f);
            Fx.dustRing(now, Fx.color("copper"), 5.5, 36, 2f);
            Fx.worldSound(now, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.5f, 0.7f);
            Fx.worldSound(now, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.6f);
            for (Player player : nearbyPlayers(6)) {
                Vector push = player.getLocation().toVector().subtract(now.toVector()).setY(0);
                if (push.lengthSquared() < 0.01) push = new Vector(0, 0, 1);
                player.setVelocity(push.normalize().multiply(1.1).setY(0.7));
                player.damage(6 * damageMultiplier, entity);
            }
        }, 18L);
    }

    private void charge(Collection<Player> players) {
        Player target = players.stream().max(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(entity.getLocation()))).orElse(null);
        if (target == null) return;
        Vector direction = target.getLocation().toVector().subtract(entity.getLocation().toVector()).setY(0);
        if (direction.lengthSquared() < 4) return;
        Fx.worldSound(entity.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2f, 1.1f);
        entity.getWorld().spawnParticle(Particle.CLOUD, entity.getLocation(), 20, 0.5, 0.2, 0.5, 0.05);
        entity.setVelocity(direction.normalize().multiply(enraged ? 2.0 : 1.6).setY(0.3));
    }

    // ── Brood Mother ──

    private void webSnare(Collection<Player> players) {
        List<Player> targets = new ArrayList<>(players);
        int count = Math.min(targets.size(), enraged ? 3 : 2);
        for (int i = 0; i < count; i++) {
            Player target = targets.remove(ThreadLocalRandom.current().nextInt(targets.size()));
            Block block = target.getLocation().getBlock();
            if (!block.getType().isAir()) continue;
            block.setType(Material.COBWEB, false);
            Location key = block.getLocation();
            webs.add(key);
            block.getWorld().spawnParticle(Particle.ITEM_COBWEB, key.clone().add(0.5, 0.5, 0.5), 20, 0.4, 0.4, 0.4, 0.05);
            host.plugin().getServer().getScheduler().runTaskLater(host.plugin(), () -> clearWeb(key), 80L);
        }
        Fx.worldSound(entity.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1.5f, 0.5f);
    }

    private void clearWeb(Location location) {
        if (!webs.remove(location)) return;
        Block block = location.getBlock();
        if (block.getType() == Material.COBWEB) block.setType(Material.AIR, false);
    }

    // ── Rimeborn ──

    private void frostNova() {
        Location center = entity.getLocation();
        telegraph(center, 7, Fx.color("sky"));
        host.plugin().getServer().getScheduler().runTaskLater(host.plugin(), () -> {
            if (!isAlive()) return;
            Location now = entity.getLocation();
            Fx.ring(now, Particle.SNOWFLAKE, 3.5, 30);
            Fx.ring(now, Particle.SNOWFLAKE, 7, 50);
            Fx.worldSound(now, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.6f);
            for (Player player : nearbyPlayers(7)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
                player.setFreezeTicks(Math.min(player.getMaxFreezeTicks() + 100, player.getFreezeTicks() + 140));
                player.damage(2 * damageMultiplier, entity);
                Fx.sound(player, Sound.ENTITY_PLAYER_HURT_FREEZE, 1f, 1f);
            }
        }, 20L);
    }

    private void blink(Player target) {
        Vector behind = target.getLocation().getDirection().setY(0);
        if (behind.lengthSquared() < 0.01) behind = new Vector(1, 0, 0);
        Location destination = target.getLocation().subtract(behind.normalize().multiply(4));
        if (!destination.getBlock().isPassable() || !destination.clone().add(0, 1, 0).getBlock().isPassable()) return;
        entity.getWorld().spawnParticle(Particle.CLOUD, entity.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.05);
        destination.setDirection(target.getLocation().toVector().subtract(destination.toVector()));
        entity.teleport(destination);
        entity.getWorld().spawnParticle(Particle.SNOWFLAKE, destination.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
        Fx.worldSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.4f);
    }

    // ── Helpers ──

    /** Shows the danger zone of an incoming ability so players can dodge it. */
    private void telegraph(Location center, double radius, Color color) {
        Fx.dustRing(center, color, radius, (int) (radius * 8), 1.6f);
        Fx.dustRing(center, color, radius * 0.5, (int) (radius * 4), 1.2f);
        Fx.worldSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.8f);
    }

    private static Location safeNear(Location wanted, Location fallback) {
        Block feet = wanted.getBlock();
        if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && !feet.getRelative(0, -1, 0).isPassable()) return wanted;
        return fallback.clone();
    }

    /** Removes the boss, its bar viewers and leftover webs. */
    public void cleanup(boolean removeEntity) {
        for (Location web : List.copyOf(webs)) clearWeb(web);
        if (removeEntity && entity.isValid()) {
            entity.getWorld().spawnParticle(Particle.POOF, entity.getLocation().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.05);
            entity.remove();
        }
    }
}
