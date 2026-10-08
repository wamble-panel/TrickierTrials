package de.t14d3.trickiertrials.mob;

import de.t14d3.trickiertrials.Settings;
import de.t14d3.trickiertrials.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Breeze;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Makes trial mobs scale with the gear of the players, the current wave and the party size. */
public final class MobScaler {

    public enum Tier {
        DEFAULT(1.0, 1.0),
        NORMAL(1.1, 1.0),
        HARD(1.5, 1.6),
        EXTREME(2.0, 2.2);

        final double health;
        final double damage;

        Tier(double health, double damage) {
            this.health = health;
            this.damage = damage;
        }
    }

    /**
     * Everything that influences how strong a freshly spawned mob is. The multipliers come from the party's
     * Trial Rank, run modifiers and wave events.
     */
    public record Context(Tier tier, int wave, int players, boolean ominous,
                          double healthMultiplier, double damageMultiplier, double speedMultiplier, double eliteBonus) {

        public Context(Tier tier, int wave, int players, boolean ominous) {
            this(tier, wave, players, ominous, 1, 1, 1, 0);
        }
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private MobScaler() {
    }

    /** Average gear score per player. */
    public static Tier tierFor(Collection<? extends Player> players) {
        if (players.isEmpty()) return Tier.DEFAULT;
        int total = 0;
        for (Player player : players) total += gearScore(player);
        int average = total / players.size();
        if (average >= 35) return Tier.EXTREME;
        if (average >= 20) return Tier.HARD;
        if (average >= 10) return Tier.NORMAL;
        return Tier.DEFAULT;
    }

    public static int gearScore(Player player) {
        int score = 0;
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item == null) continue;
            String name = item.getType().name();
            if (item.getType() == Material.ELYTRA) score += 10;
            else if (name.startsWith("NETHERITE_")) score += 5;
            else if (name.startsWith("DIAMOND_")) score += 4;
            else if (name.startsWith("IRON_") || name.startsWith("CHAINMAIL_") || name.startsWith("COPPER_")) score += 3;
            else if (name.startsWith("GOLDEN_")) score += 2;
            else if (name.startsWith("LEATHER_") || item.getType() == Material.TURTLE_HELMET) score += 1;
        }
        boolean[] seen = new boolean[3];
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            Material type = item.getType();
            String name = type.name();
            boolean weapon = name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_SPEAR");
            if (type == Material.MACE && !seen[0]) {
                seen[0] = true;
                score += 10;
            } else if (weapon && name.startsWith("NETHERITE_") && !seen[1]) {
                seen[1] = true;
                score += 10;
            } else if (weapon && name.startsWith("DIAMOND_") && !seen[2]) {
                seen[2] = true;
                score += 5;
            } else if (type == Material.TOTEM_OF_UNDYING) {
                score += 2;
            }
        }
        return score;
    }

    /** Applies tier, wave, party and elite scaling. Returns the rolled affixes. */
    public static List<Affix> apply(LivingEntity entity, Context context, Settings settings, boolean allowElite) {
        Keys.markTrialMob(entity);
        if (settings.glowing) entity.setGlowing(true);

        Tier tier = settings.gearScaling ? context.tier() : Tier.DEFAULT;
        int extraPlayers = Math.max(0, context.players() - 1);
        int waveIndex = Math.max(0, context.wave() - 1);
        double health = tier.health * (1 + settings.healthPerWave * waveIndex) * (1 + settings.healthPerExtraPlayer * extraPlayers)
                * context.healthMultiplier();
        double damage = tier.damage * (1 + settings.damagePerWave * waveIndex) * (1 + settings.damagePerExtraPlayer * extraPlayers)
                * context.damageMultiplier();
        if (context.speedMultiplier() != 1) Affix.multiply(entity, Attribute.MOVEMENT_SPEED, context.speedMultiplier());

        if (settings.strengthenMobs) {
            equip(entity, tier);
        }

        List<Affix> affixes = allowElite ? rollAffixes(context, settings) : Collections.emptyList();
        if (!affixes.isEmpty()) health *= 1 + settings.eliteHealthBonus;

        if (settings.strengthenMobs || !affixes.isEmpty() || context.healthMultiplier() != 1 || context.damageMultiplier() != 1) {
            scaleHealth(entity, health);
            Affix.multiply(entity, Attribute.ATTACK_DAMAGE, damage);
        }

        for (Affix affix : affixes) affix.apply(entity);
        if (!affixes.isEmpty()) {
            entity.getPersistentDataContainer().set(Keys.AFFIXES, PersistentDataType.STRING,
                    affixes.stream().map(Enum::name).collect(Collectors.joining(",")));
            entity.customName(eliteName(entity, affixes));
            entity.setCustomNameVisible(true);
        }

        if (settings.easterEgg && entity instanceof Breeze && ThreadLocalRandom.current().nextDouble() < 0.05) {
            entity.customName(Component.text(settings.easterEggName));
            entity.setCustomNameVisible(true);
            scaleHealth(entity, 4);
            Affix.multiply(entity, Attribute.MOVEMENT_SPEED, 2.0);
        }
        return affixes;
    }

    public static void scaleHealth(LivingEntity entity, double multiplier) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return;
        maxHealth.setBaseValue(Math.min(1024, maxHealth.getBaseValue() * multiplier));
        entity.setHealth(maxHealth.getValue());
    }

    private static List<Affix> rollAffixes(Context context, Settings settings) {
        if (!settings.elitesEnabled) return Collections.emptyList();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double chance = settings.eliteBaseChance + settings.eliteChancePerWave * Math.max(0, context.wave() - 1);
        if (context.ominous()) chance += settings.eliteOminousBonus;
        chance += context.eliteBonus();
        if (random.nextDouble() >= Math.min(settings.eliteMaxChance + context.eliteBonus(), chance)) return Collections.emptyList();

        List<Affix> pool = new ArrayList<>(Arrays.stream(Affix.values()).filter(a -> !settings.disabledAffixes.contains(a.name())).toList());
        if (pool.isEmpty()) return Collections.emptyList();
        Collections.shuffle(pool);
        int count = 1;
        if (context.wave() >= 4 && random.nextDouble() < 0.4) count++;
        if (context.ominous() && context.wave() >= 7 && random.nextDouble() < 0.4) count++;
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    public static Component eliteName(LivingEntity entity, List<Affix> affixes) {
        Component name = Component.text("✦ ", NamedTextColor.GOLD);
        for (Affix affix : affixes) {
            name = name.append(Component.text(affix.displayName() + " ", affix.color()));
        }
        return name.append(Component.translatable(entity.getType(), NamedTextColor.WHITE)).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Affix> affixesOf(LivingEntity entity) {
        String raw = entity.getPersistentDataContainer().get(Keys.AFFIXES, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        return Arrays.stream(raw.split(",")).map(Affix::byName).filter(Objects::nonNull).toList();
    }

    // ───────────────────────────── Equipment ─────────────────────────────

    private static void equip(LivingEntity entity, Tier tier) {
        boolean humanoid = entity instanceof Zombie || entity instanceof AbstractSkeleton;
        EntityEquipment equipment = entity.getEquipment();
        if (!humanoid || equipment == null) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            Material material = switch (tier) {
                case EXTREME -> random.nextDouble() < 0.5 ? armor("NETHERITE", slot) : random.nextDouble() < 0.8 ? armor("DIAMOND", slot) : null;
                case HARD -> random.nextDouble() < 0.6 ? armor("IRON", slot)
                        : random.nextDouble() < 0.05 ? armor("DIAMOND", slot)
                        : random.nextDouble() < 0.2 ? armor("LEATHER", slot) : null;
                case NORMAL -> random.nextDouble() < 0.2 ? armor("LEATHER", slot) : random.nextDouble() < 0.1 ? armor("IRON", slot) : null;
                case DEFAULT -> random.nextDouble() < 0.25 ? armor("LEATHER", slot) : null;
            };
            if (material != null) {
                equipment.setItem(slot, new ItemStack(material));
                equipment.setDropChance(slot, 0f);
            }
        }

        if (entity instanceof Zombie && equipment.getItemInMainHand().getType().isAir()) {
            double roll = random.nextDouble();
            Material weapon = switch (tier) {
                case EXTREME -> roll < 0.4 ? Material.NETHERITE_SWORD : roll < 0.7 ? Material.DIAMOND_SPEAR : roll < 0.9 ? Material.DIAMOND_SWORD : null;
                case HARD -> roll < 0.3 ? Material.IRON_SWORD : roll < 0.45 ? Material.IRON_SPEAR : roll < 0.55 ? Material.DIAMOND_SWORD : null;
                case NORMAL -> roll < 0.2 ? Material.STONE_SWORD : roll < 0.3 ? Material.COPPER_SPEAR : roll < 0.4 ? Material.IRON_SWORD : null;
                case DEFAULT -> roll < 0.15 ? Material.WOODEN_SWORD : roll < 0.25 ? Material.STONE_SWORD : null;
            };
            if (weapon != null) {
                equipment.setItemInMainHand(new ItemStack(weapon));
                equipment.setDropChance(EquipmentSlot.HAND, 0f);
            }
        }
    }

    private static Material armor(String material, EquipmentSlot slot) {
        String piece = switch (slot) {
            case HEAD -> "_HELMET";
            case CHEST -> "_CHESTPLATE";
            case LEGS -> "_LEGGINGS";
            default -> "_BOOTS";
        };
        return Material.matchMaterial(material + piece);
    }
}
