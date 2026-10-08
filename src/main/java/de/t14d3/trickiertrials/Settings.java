package de.t14d3.trickiertrials;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/** Snapshot of all configuration values. Rebuilt on every reload. */
public final class Settings {

    /** Every mob a vanilla trial spawner can spawn. */
    public static final Set<EntityType> DEFAULT_MOB_POOL = EnumSet.of(
            EntityType.BREEZE, EntityType.BOGGED, EntityType.ZOMBIE, EntityType.HUSK, EntityType.SKELETON,
            EntityType.STRAY, EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.SLIME, EntityType.SILVERFISH);

    // Chamber protection
    public final boolean protectionEnabled;
    public final Set<Material> protectedBlocks = EnumSet.noneOf(Material.class);
    public final boolean regenerateBrokenBlocks;
    public final int regenerateDelay;
    public final boolean decayPlacedBlocks;
    public final int decayDelay;
    public final int miningFatigueLevel;

    // Trial mobs
    public final boolean strengthenMobs;
    public final boolean glowing;
    public final boolean gearScaling;
    public final double healthPerExtraPlayer;
    public final double damagePerExtraPlayer;
    public final boolean restrictDrops;
    public final Set<Material> allowedDrops = EnumSet.noneOf(Material.class);
    public final Set<EntityType> mobPool = EnumSet.noneOf(EntityType.class);
    public final boolean easterEgg;
    public final String easterEggName;

    // Elites
    public final boolean elitesEnabled;
    public final double eliteBaseChance;
    public final double eliteChancePerWave;
    public final double eliteMaxChance;
    public final double eliteOminousBonus;
    public final double eliteHealthBonus;
    public final int eliteBonusExp;
    public final double eliteKeyChance;
    public final Set<String> disabledAffixes = new HashSet<>();

    // Waves
    public final int killsBase;
    public final int killsPerExtraPlayer;
    public final int killsPerWave;
    public final double healthPerWave;
    public final double damagePerWave;
    public final int bossEvery;
    public final int finalWave;
    public final int intermission;
    public final double waveClearHeal;
    public final boolean reinforcements;
    public final boolean wakeSpawners;
    public final boolean fallbackSpawns;
    public final boolean spawnerRewardsOnce;
    public final int reinforcementDelay;

    // Bosses
    public final boolean bossesEnabled;
    public final double bossHealthPerExtraPlayer;
    public final double bossOminousMultiplier;
    public final double bossFinalMultiplier;
    public final double bossEnrageThreshold;
    public final int bossExperience;
    public final List<Reward> bossRewards;
    public final List<Reward> victoryRewards;
    private final ConfigurationSection bossTypes;

    // Score
    public final int scoreKill;
    public final int scoreElite;
    public final int scoreBoss;
    public final int scoreWaveClear;
    public final int scoreVictory;
    public final int scoreDeathPenalty;
    public final int comboWindowTicks;
    public final double comboPerKill;
    public final double comboMax;

    // Session
    public final boolean sessionsEnabled;
    public final int fallbackRadius;
    public final int idleTimeout;
    public final int victoryCooldown;
    public final boolean bossbar;
    public final boolean actionbar;
    public final boolean titles;
    public final boolean sounds;
    public final boolean broadcastVictories;

    // Vaults
    public final boolean vaultResetEnabled;
    public final long vaultCooldownMillis;

    public Settings(FileConfiguration c, Logger logger) {
        protectionEnabled = c.getBoolean("chamber-protection.enabled", true);
        for (String name : c.getStringList("chamber-protection.blocks-to-protect")) {
            Material material = Material.matchMaterial(name);
            if (material == null) logger.warning("Invalid material in blocks-to-protect: " + name);
            else protectedBlocks.add(material);
        }
        regenerateBrokenBlocks = c.getBoolean("chamber-protection.regenerate-broken-blocks", true);
        regenerateDelay = c.getInt("chamber-protection.regenerate-delay", 10);
        decayPlacedBlocks = c.getBoolean("chamber-protection.decay-placed-blocks", true);
        decayDelay = c.getInt("chamber-protection.decay-delay", 10);
        miningFatigueLevel = c.getInt("chamber-protection.mining-fatigue-level", 2);

        strengthenMobs = c.getBoolean("trial-mobs.strengthen", true);
        glowing = c.getBoolean("trial-mobs.glowing", true);
        gearScaling = c.getBoolean("trial-mobs.gear-scaling", true);
        healthPerExtraPlayer = c.getDouble("trial-mobs.per-extra-player.health", 0.15);
        damagePerExtraPlayer = c.getDouble("trial-mobs.per-extra-player.damage", 0.05);
        restrictDrops = c.getBoolean("trial-mobs.restrict-drops", true);
        for (String name : c.getStringList("trial-mobs.allowed-drops")) {
            Material material = Material.matchMaterial(name);
            if (material == null) logger.warning("Invalid material in allowed-drops: " + name);
            else allowedDrops.add(material);
        }
        for (String name : c.getStringList("trial-mobs.mob-pool")) {
            try {
                mobPool.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid entity type in mob-pool: " + name);
            }
        }
        if (mobPool.isEmpty()) mobPool.addAll(DEFAULT_MOB_POOL);
        easterEgg = c.getBoolean("trial-mobs.easter-egg", false);
        easterEggName = c.getString("trial-mobs.easter-egg-name", "Klein Tiade");

        elitesEnabled = c.getBoolean("elites.enabled", true);
        eliteBaseChance = c.getDouble("elites.base-chance", 0.08);
        eliteChancePerWave = c.getDouble("elites.chance-per-wave", 0.02);
        eliteMaxChance = c.getDouble("elites.max-chance", 0.45);
        eliteOminousBonus = c.getDouble("elites.ominous-bonus", 0.10);
        eliteHealthBonus = c.getDouble("elites.health-bonus", 0.40);
        eliteBonusExp = c.getInt("elites.bonus-exp", 15);
        eliteKeyChance = c.getDouble("elites.key-drop-chance", 0.04);
        for (String name : c.getStringList("elites.disabled-affixes")) disabledAffixes.add(name.toUpperCase(Locale.ROOT));

        killsBase = Math.max(1, c.getInt("waves.kills-base", 8));
        killsPerExtraPlayer = c.getInt("waves.kills-per-extra-player", 4);
        killsPerWave = c.getInt("waves.kills-per-wave", 2);
        healthPerWave = c.getDouble("waves.health-per-wave", 0.08);
        damagePerWave = c.getDouble("waves.damage-per-wave", 0.05);
        bossEvery = c.getInt("waves.boss-every", 3);
        finalWave = c.getInt("waves.final-wave", 9);
        intermission = Math.max(1, c.getInt("waves.intermission", 6));
        waveClearHeal = c.getDouble("waves.wave-clear-heal", 6.0);
        reinforcements = c.getBoolean("waves.reinforcements", true);
        wakeSpawners = c.getBoolean("waves.wake-spawners", true);
        fallbackSpawns = c.getBoolean("waves.fallback-spawns", true);
        spawnerRewardsOnce = c.getBoolean("waves.spawner-rewards-once", true);
        reinforcementDelay = Math.max(1, c.getInt("waves.reinforcement-delay", 5));

        bossesEnabled = c.getBoolean("bosses.enabled", true);
        bossHealthPerExtraPlayer = c.getDouble("bosses.health-per-extra-player", 0.5);
        bossOminousMultiplier = c.getDouble("bosses.ominous-multiplier", 1.4);
        bossFinalMultiplier = c.getDouble("bosses.final-multiplier", 1.5);
        bossEnrageThreshold = c.getDouble("bosses.enrage-threshold", 0.35);
        bossExperience = c.getInt("bosses.experience", 120);
        bossRewards = Reward.parse(c.getStringList("bosses.rewards"), logger);
        victoryRewards = Reward.parse(c.getStringList("bosses.victory-rewards"), logger);
        bossTypes = c.getConfigurationSection("bosses.types");

        scoreKill = c.getInt("score.kill", 10);
        scoreElite = c.getInt("score.elite", 40);
        scoreBoss = c.getInt("score.boss", 300);
        scoreWaveClear = c.getInt("score.wave-clear", 100);
        scoreVictory = c.getInt("score.victory", 1000);
        scoreDeathPenalty = c.getInt("score.death-penalty", 150);
        comboWindowTicks = Math.max(1, c.getInt("combo.window", 6)) * 20;
        comboPerKill = c.getDouble("combo.multiplier-per-kill", 0.1);
        comboMax = c.getDouble("combo.max-multiplier", 3.0);

        sessionsEnabled = c.getBoolean("session.enabled", true);
        fallbackRadius = c.getInt("session.fallback-radius", 40);
        idleTimeout = Math.max(5, c.getInt("session.idle-timeout", 30));
        victoryCooldown = c.getInt("session.victory-cooldown", 600);
        bossbar = c.getBoolean("session.bossbar", true);
        actionbar = c.getBoolean("session.actionbar", true);
        titles = c.getBoolean("session.titles", true);
        sounds = c.getBoolean("session.sounds", true);
        broadcastVictories = c.getBoolean("session.broadcast-victories", true);

        vaultResetEnabled = c.getBoolean("vault-reset.enabled", true);
        long cooldown = c.getLong("vault-reset.cooldown", 86400);
        vaultCooldownMillis = cooldown < 0 ? -1 : cooldown * 1000L;
    }

    public boolean bossEnabled(String id) {
        return bossTypes == null || bossTypes.getBoolean(id + ".enabled", true);
    }

    public String bossName(String id, String fallback) {
        return bossTypes == null ? fallback : bossTypes.getString(id + ".name", fallback);
    }

    public double bossHealth(String id, double fallback) {
        return bossTypes == null ? fallback : bossTypes.getDouble(id + ".health", fallback);
    }

    public double bossDamage(String id) {
        return bossTypes == null ? 1.0 : bossTypes.getDouble(id + ".damage", 1.0);
    }

    /** Moves values from the flat v1 config layout to the v2 layout. */
    public static boolean migrate(FileConfiguration c) {
        if (c.getInt("config-version", 1) >= 2) return false;
        move(c, "blocks-to-protect", "chamber-protection.blocks-to-protect");
        move(c, "decay-placed-blocks", "chamber-protection.decay-placed-blocks");
        move(c, "decay-delay", "chamber-protection.decay-delay");
        move(c, "regenerate-broken-blocks", "chamber-protection.regenerate-broken-blocks");
        move(c, "regenerate-delay", "chamber-protection.regenerate-delay");
        move(c, "mining-fatigue-level", "chamber-protection.mining-fatigue-level");
        move(c, "strengthen-trial-mobs", "trial-mobs.strengthen");
        move(c, "glowing-effect", "trial-mobs.glowing");
        move(c, "easter-egg", "trial-mobs.easter-egg");
        move(c, "easter-egg-name", "trial-mobs.easter-egg-name");
        if (c.contains("trial-vault-reset-time")) {
            long millis = c.getLong("trial-vault-reset-time");
            c.set("vault-reset.enabled", millis >= 0);
            if (millis >= 0) c.set("vault-reset.cooldown", millis / 1000L);
            c.set("trial-vault-reset-time", null);
        }
        c.set("modules", null);
        c.set("config-version", 2);
        return true;
    }

    private static void move(FileConfiguration c, String from, String to) {
        if (!c.contains(from, true)) return;
        Object value = c.get(from);
        // v1 accidentally wrote values like "10 #comment" for missing keys
        if (value instanceof String s && s.contains("#")) {
            try {
                value = Integer.parseInt(s.substring(0, s.indexOf('#')).trim());
            } catch (NumberFormatException ignored) {
                value = null;
            }
        }
        if (value != null) c.set(to, value);
        c.set(from, null);
    }

    /** A loot entry in the form "MATERIAL amount chance". */
    public record Reward(Material material, int amount, double chance) {

        static List<Reward> parse(List<String> lines, Logger logger) {
            List<Reward> rewards = new ArrayList<>();
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                Material material = Material.matchMaterial(parts[0]);
                if (material == null || !material.isItem()) {
                    logger.warning("Invalid reward material: " + line);
                    continue;
                }
                try {
                    int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                    double chance = parts.length > 2 ? Double.parseDouble(parts[2]) : 1.0;
                    rewards.add(new Reward(material, amount, chance));
                } catch (NumberFormatException e) {
                    logger.warning("Invalid reward entry: " + line);
                }
            }
            return rewards;
        }

        /** Rolls this reward. Returns null when the roll fails. */
        public ItemStack roll(double amountMultiplier) {
            if (ThreadLocalRandom.current().nextDouble() >= chance) return null;
            int count = Math.max(1, (int) Math.round(amount * amountMultiplier));
            return new ItemStack(material, Math.min(count, material.getMaxStackSize() * 4));
        }
    }
}
