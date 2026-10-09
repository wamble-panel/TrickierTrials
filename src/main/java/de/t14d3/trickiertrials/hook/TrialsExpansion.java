package de.t14d3.trickiertrials.hook;

import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.stats.StatsStore;
import de.t14d3.trickiertrials.util.RankBadge;
import de.t14d3.trickiertrials.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * PlaceholderAPI placeholders:
 * <pre>
 * %trickiertrials_rank%            rank number (0-20)
 * %trickiertrials_rank_stars%      star badge with colours, for TAB / scoreboards / chat
 * %trickiertrials_rank_stars_hex%  star badge as &amp;#RRGGBB text, exactly as in the config
 * %trickiertrials_rank_tier%       tier name (Bronze, Silver, ...)
 * %trickiertrials_rank_roman%      rank as a roman numeral
 * %trickiertrials_progress%        wins towards the next rank (MAX at max rank)
 * %trickiertrials_progress_needed% wins the next rank needs
 * %trickiertrials_best_score% %trickiertrials_best_wave% %trickiertrials_kills%
 * %trickiertrials_bosses% %trickiertrials_victories% %trickiertrials_runs%
 * </pre>
 */
public final class TrialsExpansion extends PlaceholderExpansion {

    private final TrickierTrials plugin;

    public TrialsExpansion(TrickierTrials plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "trickiertrials";
    }

    @Override
    public String getAuthor() {
        return "t14d3";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    /**
     * Leaderboard placeholders for holograms:
     * weekly_name_N, weekly_score_N, weekly_wins_N, weekly_score, weekly_position, weekly_reset,
     * lastweek_name_N, lastweek_score_N, top_CATEGORY_name_N, top_CATEGORY_value_N.
     */
    private String boardPlaceholder(OfflinePlayer player, String key) {
        String empty = plugin.settings().weeklyEmptySlot;
        var weekly = plugin.weekly();
        switch (key) {
            case "weekly_score" -> {
                return Text.number(weekly.score(player.getUniqueId()));
            }
            case "weekly_position" -> {
                int position = weekly.position(player.getUniqueId());
                return position == 0 ? empty : String.valueOf(position);
            }
            case "weekly_reset" -> {
                return Text.duration(weekly.millisUntilReset());
            }
            default -> {
            }
        }
        String[] parts = key.split("_");
        int place;
        try {
            place = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (place < 1) return empty;
        if (key.startsWith("weekly_")) {
            var list = weekly.standings();
            if (place > list.size()) return key.startsWith("weekly_name_") ? empty : "";
            var entry = list.get(place - 1);
            if (key.startsWith("weekly_name_")) return entry.name();
            if (key.startsWith("weekly_score_")) return Text.number(entry.score());
            if (key.startsWith("weekly_wins_")) return String.valueOf(entry.wins());
            return null;
        }
        if (key.startsWith("lastweek_")) {
            String field = key.startsWith("lastweek_name_") ? "name" : key.startsWith("lastweek_score_") ? "score" : null;
            if (field == null) return null;
            String value = weekly.lastWeek(place, field);
            if (value == null) return field.equals("name") ? empty : "";
            return field.equals("score") ? Text.number(Long.parseLong(value)) : value;
        }
        if (key.startsWith("top_") && parts.length == 4) {
            StatsStore.Category category = StatsStore.Category.byName(parts[1]);
            if (category == null) return null;
            var list = plugin.stats().top(category, place);
            if (place > list.size()) return parts[2].equals("name") ? empty : "";
            var entry = list.get(place - 1);
            if (parts[2].equals("name")) return entry.name();
            if (parts[2].equals("value")) {
                return category == StatsStore.Category.WAVE || category == StatsStore.Category.RANK
                        ? Text.roman((int) entry.value()) : Text.number(entry.value());
            }
        }
        return null;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        int rank = plugin.stats().rank(player.getUniqueId());
        String key = params.toLowerCase(Locale.ROOT);
        // Players without a rank (never conquered a chamber, or no stats at all) show nothing.
        if (rank <= 0 && key.startsWith("rank")) return "";
        switch (key) {
            case "rank" -> {
                return String.valueOf(rank);
            }
            case "rank_stars" -> {
                return RankBadge.legacy(rank);
            }
            case "rank_stars_hex" -> {
                return RankBadge.raw(rank);
            }
            case "rank_tier" -> {
                return RankBadge.tier(rank);
            }
            case "rank_roman" -> {
                return rank <= 0 ? "0" : Text.roman(rank);
            }
            default -> {
            }
        }
        String board = boardPlaceholder(player, key);
        if (board != null) return board;
        if (key.equals("progress") || key.equals("progress_needed")) {
            if (rank >= plugin.settings().maxRank) return key.equals("progress") ? "MAX" : "";
            return key.equals("progress") ? String.valueOf(plugin.stats().rankProgress(player.getUniqueId()))
                    : String.valueOf(plugin.settings().rankRules().winsFor(rank + 1));
        }
        ConfigurationSection stats = plugin.stats().get(player.getUniqueId());
        String path = switch (key) {
            case "best_score" -> "best-score";
            case "best_wave" -> "best-wave";
            case "kills" -> "kills";
            case "bosses" -> "bosses";
            case "victories" -> "victories";
            case "runs" -> "runs";
            default -> null;
        };
        if (path == null) return null;
        return String.valueOf(stats == null ? 0 : stats.getLong(path));
    }
}
