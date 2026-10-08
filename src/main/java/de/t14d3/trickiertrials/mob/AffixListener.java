package de.t14d3.trickiertrials.mob;

import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.util.Fx;
import de.t14d3.trickiertrials.util.Keys;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.function.Consumer;

/** Combat behaviour of elite affixes. */
public final class AffixListener implements Listener {

    private final TrickierTrials plugin;

    public AffixListener(TrickierTrials plugin) {
        this.plugin = plugin;
    }

    private static LivingEntity attacker(Entity damager) {
        if (damager instanceof LivingEntity living) return living;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity living) return living;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEliteHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        LivingEntity mob = attacker(event.getDamager());
        if (mob == null || !Keys.isTrialMob(mob)) return;
        List<Affix> affixes = MobScaler.affixesOf(mob);
        if (affixes.isEmpty()) return;

        for (Affix affix : affixes) {
            switch (affix) {
                case VAMPIRIC -> {
                    AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
                    if (max != null) mob.setHealth(Math.min(max.getValue(), mob.getHealth() + event.getFinalDamage() * 0.5));
                    mob.getWorld().spawnParticle(Particle.HEART, mob.getEyeLocation(), 2, 0.3, 0.3, 0.3, 0);
                }
                case MOLTEN -> victim.setFireTicks(Math.max(victim.getFireTicks(), 60));
                case FROSTBITE -> {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                    victim.setFreezeTicks(Math.min(victim.getMaxFreezeTicks() + 40, victim.getFreezeTicks() + 80));
                    victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
                }
                case VENOMOUS -> victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 0));
                case GALEBORN -> {
                    victim.setVelocity(victim.getVelocity().add(new Vector(0, 0.9, 0)));
                    victim.getWorld().spawnParticle(Particle.GUST, victim.getLocation(), 1);
                    Fx.worldSound(victim.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 1.2f);
                }
                default -> {
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUndying(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || mob instanceof Player) return;
        if (event.getFinalDamage() < mob.getHealth() + mob.getAbsorptionAmount()) return;
        if (!MobScaler.affixesOf(mob).contains(Affix.UNDYING)) return;
        if (mob.getPersistentDataContainer().has(Keys.UNDYING_USED)) return;

        mob.getPersistentDataContainer().set(Keys.UNDYING_USED, PersistentDataType.BYTE, (byte) 1);
        event.setCancelled(true);
        AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
        mob.setHealth(max == null ? 10 : max.getValue() * 0.4);
        mob.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, mob.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
        Fx.worldSound(mob.getLocation(), Sound.ITEM_TOTEM_USE, 0.8f, 1.3f);
    }

    /** Death effects of affixes. {@code spawned} receives any extra mobs that should join the encounter. */
    public void onEliteDeath(LivingEntity mob, Consumer<LivingEntity> spawned) {
        List<Affix> affixes = MobScaler.affixesOf(mob);
        if (affixes.contains(Affix.VOLATILE)) {
            Location location = mob.getLocation();
            location.getWorld().spawnParticle(Particle.FLAME, location.clone().add(0, 0.5, 0), 30, 0.4, 0.4, 0.4, 0.05);
            Fx.worldSound(location, Sound.ENTITY_CREEPER_PRIMED, 1f, 1f);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> location.getWorld().createExplosion(null, location, 2.4f, false, false), 25L);
        }
        if (affixes.contains(Affix.SPLITTING) && plugin.settings().mobPool.contains(mob.getType()) && mob.getType().getEntityClass() != null) {
            Location location = mob.getLocation();
            location.getWorld().spawnParticle(Particle.WITCH, location.clone().add(0, 0.8, 0), 25, 0.4, 0.4, 0.4, 0.05);
            for (int i = 0; i < 2; i++) {
                Entity child = location.getWorld().spawn(location, mob.getType().getEntityClass(), e -> {
                    if (e instanceof LivingEntity living) {
                        MobScaler.scaleHealth(living, 0.5);
                        Affix.multiply(living, Attribute.SCALE, 0.7);
                    }
                });
                child.setVelocity(new Vector(Math.random() - 0.5, 0.4, Math.random() - 0.5));
                if (child instanceof LivingEntity living) spawned.accept(living);
            }
        }
    }
}
