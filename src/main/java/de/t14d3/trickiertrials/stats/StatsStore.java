package de.t14d3.trickiertrials.stats;

import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.session.PlayerRun;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Persistent per-player records stored in stats.yml. */
public final class StatsStore {

    public enum Category {
        SCORE("best-score", "Best score"),
        WAVE("best-wave", "Highest wave"),
        KILLS("kills", "Total kills"),
        BOSSES("bosses", "Bosses slain"),
        VICTORIES("victories", "Chambers conquered"),
        RANK("rank", "Trial Rank");

        public final String path;
        public final String label;

        Category(String path, String label) {
            this.path = path;
            this.label = label;
        }

        public static Category byName(String name) {
            for (Category category : values()) if (category.name().equalsIgnoreCase(name)) return category;
            return null;
        }
    }

    public record Entry(String name, long value) {
    }

    private final TrickierTrials plugin;
    private final File file;
    private final YamlConfiguration data;
    private boolean dirty;

    public StatsStore(TrickierTrials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private ConfigurationSection section(UUID uuid) {
        ConfigurationSection section = data.getConfigurationSection("players." + uuid);
        return section != null ? section : data.createSection("players." + uuid);
    }

    /** Records a finished run. Returns the descriptions of any personal bests that were beaten. */
    /** Direct lookup by UUID (fast - used by placeholders). */
    public ConfigurationSection get(UUID uuid) {
        return data.getConfigurationSection("players." + uuid);
    }

    public int rank(UUID uuid) {
        ConfigurationSection s = data.getConfigurationSection("players." + uuid);
        return s == null ? 0 : s.getInt("rank");
    }

    public void setRank(UUID uuid, String name, int rank) {
        ConfigurationSection s = section(uuid);
        if (name != null) s.set("name", name);
        s.set("rank", Math.max(0, rank));
        dirty = true;
    }

    /** Records a finished run; a victory raises the player's Trial Rank by one (up to {@code maxRank}). */
    public List<String> record(PlayerRun run, int wave, boolean victory, int maxRank) {
        ConfigurationSection s = section(run.uuid);
        List<String> records = new ArrayList<>();
        s.set("name", run.name);
        if (run.score > s.getLong("best-score") && s.getInt("runs") > 0) records.add("score " + run.score);
        if (wave > s.getInt("best-wave") && s.getInt("runs") > 0) records.add("wave " + wave);
        s.set("best-score", Math.max(s.getLong("best-score"), run.score));
        s.set("best-wave", Math.max(s.getInt("best-wave"), wave));
        s.set("best-combo", Math.max(s.getInt("best-combo"), run.bestCombo));
        s.set("kills", s.getLong("kills") + run.kills);
        s.set("elites", s.getLong("elites") + run.elites);
        s.set("bosses", s.getLong("bosses") + run.bosses);
        s.set("deaths", s.getLong("deaths") + run.deaths);
        s.set("runs", s.getInt("runs") + 1);
        if (victory) s.set("victories", s.getInt("victories") + 1);
        if (victory && s.getInt("rank") < maxRank) {
            s.set("rank", s.getInt("rank") + 1);
            records.add(0, "RANK:" + s.getInt("rank"));
        }
        dirty = true;
        return records;
    }

    public ConfigurationSection find(String nameOrUuid) {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return null;
        for (String key : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(key);
            if (s == null) continue;
            if (key.equalsIgnoreCase(nameOrUuid) || nameOrUuid.equalsIgnoreCase(s.getString("name", ""))) return s;
        }
        return null;
    }

    public List<Entry> top(Category category, int limit) {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String key : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(key);
            if (s == null) continue;
            long value = s.getLong(category.path);
            if (value > 0) entries.add(new Entry(s.getString("name", key.substring(0, 8)), value));
        }
        entries.sort(Comparator.comparingLong(Entry::value).reversed().thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    /** Writes the file asynchronously if anything changed. */
    public void saveAsync() {
        if (!dirty) return;
        dirty = false;
        String contents = data.saveToString();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(contents));
    }

    public void saveNow() {
        if (!dirty) return;
        dirty = false;
        write(data.saveToString());
    }

    private synchronized void write(String contents) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(file.toPath(), contents, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save stats.yml: " + e.getMessage());
        }
    }
}
