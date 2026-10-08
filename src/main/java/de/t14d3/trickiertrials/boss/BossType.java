package de.t14d3.trickiertrials.boss;

import org.bukkit.entity.Bogged;
import org.bukkit.entity.Breeze;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Zombie;

import java.util.Locale;

/** The bosses that can appear on boss waves. Each has its own set of abilities in {@link TrialBoss}. */
public enum BossType {

    TEMPEST("tempest", Breeze.class, Breeze.class, 260, 1.6, "Tempest, the Howling Gale"),
    BOG_SOVEREIGN("bog-sovereign", Bogged.class, Bogged.class, 240, 1.7, "The Bog Sovereign"),
    JUGGERNAUT("juggernaut", Zombie.class, Zombie.class, 380, 1.8, "Copperclad Juggernaut"),
    BROOD_MOTHER("brood-mother", Spider.class, CaveSpider.class, 280, 2.2, "Vexa, the Brood Mother"),
    RIMEBORN("rimeborn", Stray.class, Stray.class, 230, 1.6, "The Rimeborn Marksman");

    private final String id;
    private final Class<? extends LivingEntity> entityClass;
    private final Class<? extends LivingEntity> minionClass;
    private final double defaultHealth;
    private final double scale;
    private final String defaultName;

    BossType(String id, Class<? extends LivingEntity> entityClass, Class<? extends LivingEntity> minionClass,
             double defaultHealth, double scale, String defaultName) {
        this.id = id;
        this.entityClass = entityClass;
        this.minionClass = minionClass;
        this.defaultHealth = defaultHealth;
        this.scale = scale;
        this.defaultName = defaultName;
    }

    public String id() {
        return id;
    }

    public Class<? extends LivingEntity> entityClass() {
        return entityClass;
    }

    public Class<? extends LivingEntity> minionClass() {
        return minionClass;
    }

    public double defaultHealth() {
        return defaultHealth;
    }

    public double scale() {
        return scale;
    }

    public String defaultName() {
        return defaultName;
    }

    public static BossType byId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT).replace('_', '-');
        for (BossType type : values()) if (type.id.equals(normalized)) return type;
        return null;
    }
}
