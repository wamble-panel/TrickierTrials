package de.t14d3.trickiertrials.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/** Persistent data keys used to tag entities managed by TrickierTrials. */
public final class Keys {

    /** Kept identical to v1 so mobs spawned by older versions are still recognised. */
    public static final NamespacedKey TRIAL_SPAWNED = new NamespacedKey("trickiertrials", "trialspawned");
    public static final NamespacedKey AFFIXES = new NamespacedKey("trickiertrials", "affixes");
    public static final NamespacedKey BOSS = new NamespacedKey("trickiertrials", "boss");
    public static final NamespacedKey MINION = new NamespacedKey("trickiertrials", "minion");
    public static final NamespacedKey CELEBRATION = new NamespacedKey("trickiertrials", "celebration");
    public static final NamespacedKey UNDYING_USED = new NamespacedKey("trickiertrials", "undying_used");

    private Keys() {
    }

    public static boolean isTrialMob(Entity entity) {
        return entity.getPersistentDataContainer().has(TRIAL_SPAWNED, PersistentDataType.INTEGER);
    }

    public static void markTrialMob(Entity entity) {
        entity.getPersistentDataContainer().set(TRIAL_SPAWNED, PersistentDataType.INTEGER, 1);
    }

    public static boolean isBoss(Entity entity) {
        return entity.getPersistentDataContainer().has(BOSS, PersistentDataType.STRING);
    }
}
