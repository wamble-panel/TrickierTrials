package de.t14d3.trickiertrials.mob;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Elite modifiers, inspired by Diablo / Path of Exile monster affixes. */
public enum Affix {

    SWIFT("Swift", 0x9AD8FF),
    BRUTAL("Brutal", 0xFF5E6C),
    VAMPIRIC("Vampiric", 0xD64575),
    MOLTEN("Molten", 0xFF8A3D),
    FROSTBITE("Frostbite", 0xBDEBFF),
    VENOMOUS("Venomous", 0x7EE081),
    VOLATILE("Volatile", 0xFFB347),
    SHIELDED("Shielded", 0xF7C873),
    GALEBORN("Galeborn", 0x5CC8B5),
    SPLITTING("Splitting", 0xC792EA),
    UNDYING("Undying", 0xE6D3A3);

    private final String displayName;
    private final TextColor color;

    Affix(String displayName, int color) {
        this.displayName = displayName;
        this.color = TextColor.color(color);
    }

    public String displayName() {
        return displayName;
    }

    public TextColor color() {
        return color;
    }

    /** Applies the passive part of the affix when the mob spawns. */
    public void apply(LivingEntity entity) {
        switch (this) {
            case SWIFT -> multiply(entity, Attribute.MOVEMENT_SPEED, 1.35);
            case BRUTAL -> {
                multiply(entity, Attribute.ATTACK_DAMAGE, 1.5);
                multiply(entity, Attribute.SCALE, 1.15);
                multiply(entity, Attribute.KNOCKBACK_RESISTANCE, 1.0, 0.5);
            }
            case MOLTEN -> entity.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
            case SHIELDED -> {
                AttributeInstance max = entity.getAttribute(Attribute.MAX_ABSORPTION);
                if (max != null) max.setBaseValue(Math.max(max.getBaseValue(), 12));
                entity.setAbsorptionAmount(12);
                multiply(entity, Attribute.KNOCKBACK_RESISTANCE, 1.0, 0.6);
            }
            case UNDYING -> entity.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION, 0, false, false));
            default -> {
            }
        }
    }

    public static void multiply(LivingEntity entity, Attribute attribute, double factor) {
        multiply(entity, attribute, factor, 0);
    }

    public static void multiply(LivingEntity entity, Attribute attribute, double factor, double add) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(instance.getBaseValue() * factor + add);
    }

    public static Affix byName(String name) {
        for (Affix affix : values()) if (affix.name().equalsIgnoreCase(name)) return affix;
        return null;
    }
}
