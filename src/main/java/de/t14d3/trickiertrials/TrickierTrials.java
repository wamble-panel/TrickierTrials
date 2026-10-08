package de.t14d3.trickiertrials;

import de.t14d3.trickiertrials.chamber.ChamberProtectionListener;
import de.t14d3.trickiertrials.chamber.VaultListener;
import de.t14d3.trickiertrials.command.TrialsCommand;
import de.t14d3.trickiertrials.guard.GuardManager;
import de.t14d3.trickiertrials.hook.TrialsExpansion;
import de.t14d3.trickiertrials.mob.AffixListener;
import de.t14d3.trickiertrials.session.SessionManager;
import de.t14d3.trickiertrials.stats.StatsStore;
import de.t14d3.trickiertrials.util.Fx;
import de.t14d3.trickiertrials.util.RankBadge;
import de.t14d3.trickiertrials.util.Text;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class TrickierTrials extends JavaPlugin {

    private Settings settings;
    private StatsStore stats;
    private SessionManager sessions;
    private AffixListener affixes;
    private ChamberProtectionListener protection;
    private GuardManager guards;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        reload();

        stats = new StatsStore(this);
        sessions = new SessionManager(this);
        affixes = new AffixListener(this);
        protection = new ChamberProtectionListener(this);
        guards = new GuardManager(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(protection, this);
        pm.registerEvents(new VaultListener(this), this);
        pm.registerEvents(affixes, this);
        pm.registerEvents(sessions, this);
        pm.registerEvents(guards, this);
        sessions.startTicking();
        guards.startTicking();

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new TrialsExpansion(this).register();
            getLogger().info("Registered PlaceholderAPI placeholders (%trickiertrials_rank_stars% and more).");
        }

        PluginCommand command = getCommand("trickiertrials");
        if (command != null) {
            TrialsCommand executor = new TrialsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        if (sessions != null) sessions.shutdown();
        if (guards != null) guards.shutdown();
        if (protection != null) protection.restoreAll();
        if (stats != null) stats.saveNow();
    }

    public void reload() {
        reloadConfig();
        // Add options introduced by updates to an existing config.yml (existing values are kept).
        boolean missing = false;
        var defaults = getConfig().getDefaults();
        for (String key : defaults == null ? java.util.Set.<String>of() : defaults.getKeys(true)) {
            if (!getConfig().isSet(key)) {
                missing = true;
                break;
            }
        }
        boolean upgraded = upgradeToV3();
        if (missing || upgraded) {
            getConfig().options().copyDefaults(true);
            saveConfig();
        }
        settings = new Settings(getConfig(), getLogger());
        Text.load(getConfig());
        RankBadge.load(getConfig());
        Fx.setSounds(settings.sounds);
    }

    /**
     * v3 raised the max Trial Rank from 10 to 20 with a gentler curve. Values still on the old defaults are
     * moved to the new ones; values the owner changed are kept.
     */
    private boolean upgradeToV3() {
        var c = getConfig();
        if (!c.isSet("config-version") || c.getInt("config-version") >= 3) return false;
        Object[][] changes = {
                {"progression.max-rank", 10, 20},
                {"progression.health-per-rank", 0.08, 0.05},
                {"progression.damage-per-rank", 0.05, 0.03},
                {"progression.elite-chance-per-rank", 0.03, 0.015},
                {"progression.boss-health-per-rank", 0.10, 0.06},
                {"progression.boss-affix-rank", 3, 5},
                {"progression.reward-bonus-per-rank", 0.25, 0.15},
                {"progression.score-bonus-per-rank", 0.10, 0.06},
                {"modifiers.extra-every-ranks", 3, 5},
        };
        for (Object[] change : changes) {
            String path = (String) change[0];
            if (c.isSet(path) && c.getDouble(path) == ((Number) change[1]).doubleValue()) c.set(path, change[2]);
        }
        c.set("config-version", 3);
        getLogger().info("Updated config.yml to version 3 (Trial Rank now goes up to 20, rank badges added).");
        return true;
    }

    /** Upgrades a v1 config: keeps the old values but adopts the new, documented layout. */
    private void migrateConfig() {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        if (!Settings.migrate(old)) return;

        File backup = new File(getDataFolder(), "config-v1.yml");
        if (!file.renameTo(backup)) getLogger().warning("Could not back up the old config.yml");
        saveResource("config.yml", true);
        reloadConfig();
        for (String key : old.getKeys(true)) {
            if (!old.isConfigurationSection(key)) getConfig().set(key, old.get(key));
        }
        saveConfig();
        getLogger().info("Migrated config.yml to version 2 (old file saved as config-v1.yml).");
    }

    public Settings settings() {
        return settings;
    }

    public StatsStore stats() {
        return stats;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public GuardManager guards() {
        return guards;
    }

    public AffixListener affixes() {
        return affixes;
    }
}
