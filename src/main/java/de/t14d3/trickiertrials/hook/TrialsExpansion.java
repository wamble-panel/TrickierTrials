package de.t14d3.trickiertrials.hook;

import de.t14d3.trickiertrials.TrickierTrials;
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
