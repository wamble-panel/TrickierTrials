package de.t14d3.trickiertrials;

import de.t14d3.trickiertrials.chamber.ChamberProtectionListener;
import de.t14d3.trickiertrials.chamber.VaultListener;
import de.t14d3.trickiertrials.command.TrialsCommand;
import de.t14d3.trickiertrials.guard.GuardManager;
import de.t14d3.trickiertrials.mob.AffixListener;
import de.t14d3.trickiertrials.session.SessionManager;
import de.t14d3.trickiertrials.stats.StatsStore;
import de.t14d3.trickiertrials.util.Fx;
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
        if (missing) {
            getConfig().options().copyDefaults(true);
            saveConfig();
        }
        settings = new Settings(getConfig(), getLogger());
        Text.load(getConfig());
        Fx.setSounds(settings.sounds);
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
