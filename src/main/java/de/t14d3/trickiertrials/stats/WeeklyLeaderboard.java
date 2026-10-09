package de.t14d3.trickiertrials.stats;

import de.t14d3.trickiertrials.Settings;
import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.session.PlayerRun;
import de.t14d3.trickiertrials.util.Fx;
import de.t14d3.trickiertrials.util.Text;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A leaderboard that resets every week. When a week ends, the top players receive configurable prizes
 * (items, delivered on their next join if offline, and console commands). Stored in weekly.yml.
 */
public final class WeeklyLeaderboard implements Listener {

    public record Entry(UUID uuid, String name, long score, int wins) {
    }

    private final TrickierTrials plugin;
    private final File file;
    private YamlConfiguration data;
    private List<Entry> cache = List.of();
    private long cacheTime;
    private boolean dirty;
    private BukkitTask task;

    public WeeklyLeaderboard(TrickierTrials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "weekly.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private Settings settings() {
        return plugin.settings();
    }

    public void start() {
        checkReset();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            checkReset();
            if (dirty) save();
        }, 20L * 30, 20L * 30);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        if (dirty) save();
    }

    // ───────────────────────────── Week boundaries ─────────────────────────────

    private ZoneId zone() {
        try {
            return ZoneId.of(settings().weeklyTimezone);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    /** Start of the current leaderboard week. */
    private ZonedDateTime weekStart() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        DayOfWeek day;
        LocalTime time;
        try {
            day = DayOfWeek.valueOf(settings().weeklyResetDay.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            day = DayOfWeek.MONDAY;
        }
        try {
            time = LocalTime.parse(settings().weeklyResetTime);
        } catch (Exception e) {
            time = LocalTime.MIDNIGHT;
        }
        ZonedDateTime start = now.with(TemporalAdjusters.previousOrSame(day)).with(time).withSecond(0).withNano(0);
        if (start.isAfter(now)) start = start.minusWeeks(1);
        return start;
    }

    public long millisUntilReset() {
        return weekStart().plusWeeks(1).toInstant().toEpochMilli() - System.currentTimeMillis();
    }

    private void checkReset() {
        if (!settings().weeklyEnabled) return;
        long start = weekStart().toInstant().toEpochMilli();
        long stored = data.getLong("week-start", 0);
        if (stored == start) return;
        if (stored != 0 && stored < start) finishWeek();
        data.set("week-start", start);
        data.set("players", null);
        cache = List.of();
        dirty = true;
        save();
    }

    /** Pays out the prizes and archives the final standings as "last week". */
    private void finishWeek() {
        List<Entry> standings = compute();
        data.set("last-week", null);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < standings.size() && i < 10; i++) {
            Entry entry = standings.get(i);
            data.set("last-week." + (i + 1) + ".name", entry.name());
            data.set("last-week." + (i + 1) + ".score", entry.score());
            data.set("last-week." + (i + 1) + ".wins", entry.wins());
        }
        for (int place = 1; place <= standings.size(); place++) {
            ConfigurationSection prize = plugin.getConfig().getConfigurationSection("weekly.prizes." + place);
            if (prize == null) continue;
            Entry winner = standings.get(place - 1);
            if (winner.score() < settings().weeklyMinScore) continue;
            names.add("#" + place + " " + winner.name());
            for (String command : prize.getStringList("commands")) {
                String line = command.replace("%player%", winner.name()).replace("%place%", String.valueOf(place));
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), line);
            }
            List<String> items = prize.getStringList("items");
            if (!items.isEmpty()) {
                List<String> pending = new ArrayList<>(data.getStringList("pending." + winner.uuid()));
                for (String item : items) pending.add(place + ";" + item);
                data.set("pending." + winner.uuid(), pending);
            }
            Player online = plugin.getServer().getPlayer(winner.uuid());
            if (online != null) deliver(online);
        }
        if (!names.isEmpty()) {
            plugin.getServer().broadcast(Text.prefixed("weekly-winners", Text.ph("winners", String.join(", ", names))));
        }
    }

    // ───────────────────────────── Scores ─────────────────────────────

    /** Adds a finished run to this week's standings. */
    public void record(PlayerRun run, boolean victory) {
        if (!settings().weeklyEnabled) return;
        checkReset();
        String path = "players." + run.uuid + ".";
        data.set(path + "name", run.name);
        long score = settings().weeklyMetricBest ? Math.max(data.getLong(path + "score"), run.score)
                : data.getLong(path + "score") + run.score;
        data.set(path + "score", score);
        if (victory) data.set(path + "wins", data.getInt(path + "wins") + 1);
        cacheTime = 0;
        dirty = true;
    }

    private List<Entry> compute() {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String key : players.getKeys(false)) {
            ConfigurationSection s = players.getConfigurationSection(key);
            if (s == null || s.getLong("score") <= 0) continue;
            try {
                entries.add(new Entry(UUID.fromString(key), s.getString("name", key.substring(0, 8)), s.getLong("score"), s.getInt("wins")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        entries.sort(Comparator.comparingLong(Entry::score).reversed().thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return entries;
    }

    /** Current standings, cached for 10 seconds. */
    public List<Entry> standings() {
        long now = System.currentTimeMillis();
        if (now - cacheTime > 10_000) {
            cache = List.copyOf(compute());
            cacheTime = now;
        }
        return cache;
    }

    /** 1-based position of a player, or 0 if not on the board. */
    public int position(UUID uuid) {
        List<Entry> list = standings();
        for (int i = 0; i < list.size(); i++) if (list.get(i).uuid().equals(uuid)) return i + 1;
        return 0;
    }

    public long score(UUID uuid) {
        return data.getLong("players." + uuid + ".score");
    }

    public String lastWeek(int place, String field) {
        return data.getString("last-week." + place + "." + field);
    }

    // ───────────────────────────── Prize delivery ─────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) deliver(player);
        }, 40L);
    }

    private void deliver(Player player) {
        List<String> pending = data.getStringList("pending." + player.getUniqueId());
        if (pending.isEmpty()) return;
        int place = 0;
        for (String raw : pending) {
            String[] split = raw.split(";", 2);
            if (split.length != 2) continue;
            place = Integer.parseInt(split[0]);
            for (Settings.Reward reward : Settings.Reward.parseLines(List.of(split[1]), plugin.getLogger())) {
                ItemStack item = reward.roll(1);
                if (item != null) player.getInventory().addItem(item).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
        }
        data.set("pending." + player.getUniqueId(), null);
        dirty = true;
        Text.send(player, "weekly-prize", Text.ph("place", place));
        Fx.sound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void save() {
        dirty = false;
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save weekly.yml: " + e.getMessage());
        }
    }

    /** Ends the current week right now (admin), paying out prizes. */
    public void forceReset() {
        finishWeek();
        data.set("players", null);
        data.set("week-start", weekStart().toInstant().toEpochMilli());
        cache = List.of();
        cacheTime = 0;
        dirty = true;
        save();
    }
}
