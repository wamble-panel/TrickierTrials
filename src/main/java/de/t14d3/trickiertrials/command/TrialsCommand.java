package de.t14d3.trickiertrials.command;

import de.t14d3.trickiertrials.TrickierTrials;
import de.t14d3.trickiertrials.boss.BossType;
import de.t14d3.trickiertrials.guard.GuardManager;
import de.t14d3.trickiertrials.guard.Warden;
import de.t14d3.trickiertrials.session.Modifier;
import de.t14d3.trickiertrials.session.TrialSession;
import de.t14d3.trickiertrials.stats.StatsStore;
import de.t14d3.trickiertrials.util.RankBadge;
import de.t14d3.trickiertrials.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /trials [info|stats|top|boss|end|reload] */
public final class TrialsCommand implements TabExecutor {

    private static final String ADMIN = "trickiertrials.admin";
    private static final String[] RANK_COLORS = {"gold", "light", "copper"};

    private final TrickierTrials plugin;

    public TrialsCommand(TrickierTrials plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!checkAdmin(sender)) return true;
                plugin.reload();
                Text.send(sender, "reload");
            }
            case "info" -> info(sender);
            case "stats" -> stats(sender, args.length > 1 ? args[1] : null);
            case "top" -> top(sender, args.length > 1 ? args[1] : "score");
            case "boss" -> boss(sender, args);
            case "warden" -> warden(sender, args);
            case "rank" -> rank(sender, args);
            case "end" -> {
                if (!checkAdmin(sender)) return true;
                if (!(sender instanceof Player player)) {
                    Text.send(sender, "player-only");
                    return true;
                }
                TrialSession session = plugin.sessions().sessionOf(player);
                if (session == null) {
                    Text.send(sender, "not-in-session");
                    return true;
                }
                plugin.sessions().end(session, false, false);
                Text.send(sender, "session-ended-admin");
            }
            default -> {
                for (String line : plugin.getConfig().getStringList("messages.help")) {
                    sender.sendMessage(Text.parse(line, Text.ph("version", plugin.getPluginMeta().getVersion())));
                }
            }
        }
        return true;
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN)) return true;
        Text.send(sender, "no-permission");
        return false;
    }

    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Text.send(sender, "player-only");
            return;
        }
        TrialSession session = plugin.sessions().sessionOf(player);
        if (session == null) {
            Text.send(sender, "not-in-session");
            return;
        }
        int finalWave = plugin.settings().finalWave;
        Text.send(sender, "info",
                Text.ph("wave", Text.roman(Math.max(1, session.wave()))),
                Text.ph("final", finalWave > 0 ? "/" + Text.roman(finalWave) : ""),
                Text.ph("players", session.players().size()),
                Text.ph("score", Text.number(session.teamScore())),
                Text.ph("time", Text.duration(session.elapsed())),
                Text.ph("ominous", session.ominous() ? Text.parse(" <dark_gray>·</dark_gray> <ominous>Ominous</ominous>") : Component.empty()),
                Text.ph("rank", session.rank() <= 0 ? "-" : Text.roman(session.rank())),
                Text.ph("stars", RankBadge.component(session.rank())),
                Text.ph("modifiers", Text.parse(session.modifiers().isEmpty() ? "<muted>none</muted>"
                        : String.join("<dark_gray>, </dark_gray>", session.modifiers().stream().map(Modifier::mini).toList()))));
    }

    private void stats(CommandSender sender, String target) {
        String name = target != null ? target : sender instanceof Player player ? player.getUniqueId().toString() : null;
        if (name == null) {
            Text.send(sender, "player-only");
            return;
        }
        ConfigurationSection s = plugin.stats().find(name);
        if (s == null) {
            Text.send(sender, "unknown-player", Text.ph("name", target != null ? target : sender.getName()));
            return;
        }
        for (String line : plugin.getConfig().getStringList("messages.stats")) {
            sender.sendMessage(Text.parse(line,
                    Text.ph("name", s.getString("name", name)),
                    Text.ph("best_score", Text.number(s.getLong("best-score"))),
                    Text.ph("best_wave", Text.roman(Math.max(1, s.getInt("best-wave")))),
                    Text.ph("kills", Text.number(s.getLong("kills"))),
                    Text.ph("elites", Text.number(s.getLong("elites"))),
                    Text.ph("bosses", Text.number(s.getLong("bosses"))),
                    Text.ph("victories", s.getInt("victories")),
                    Text.ph("runs", s.getInt("runs")),
                    Text.ph("best_combo", s.getInt("best-combo")),
                    Text.ph("rank", s.getInt("rank") <= 0 ? "-" : Text.roman(s.getInt("rank"))),
                    Text.ph("stars", RankBadge.component(s.getInt("rank"))),
                    Text.ph("progress", s.getInt("rank") >= plugin.settings().maxRank ? "MAX"
                            : s.getInt("rank-progress") + "/" + plugin.settings().rankRules().winsFor(s.getInt("rank") + 1))));
        }
    }

    private void top(CommandSender sender, String categoryName) {
        StatsStore.Category category = StatsStore.Category.byName(categoryName);
        if (category == null) category = StatsStore.Category.SCORE;
        List<StatsStore.Entry> entries = plugin.stats().top(category, 10);
        sender.sendMessage(Text.parse(Text.raw("top-header"), Text.ph("category", category.label)));
        if (entries.isEmpty()) {
            sender.sendMessage(Text.msg("top-empty"));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            StatsStore.Entry entry = entries.get(i);
            String color = i < RANK_COLORS.length ? RANK_COLORS[i] : "muted";
            String value = category == StatsStore.Category.WAVE || category == StatsStore.Category.RANK
                    ? Text.roman((int) entry.value()) : Text.number(entry.value());
            sender.sendMessage(Text.parse(Text.raw("top-entry").replace("<rank_color>", "<" + color + ">").replace("</rank_color>", "</" + color + ">"),
                    Text.ph("rank", i + 1), Text.ph("name", entry.name()),
                    Text.ph("value", category == StatsStore.Category.RANK ? RankBadge.component((int) entry.value()) : Text.parse("<gold>" + value + "</gold>"))));
        }
    }

    private void boss(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) return;
        if (!(sender instanceof Player player)) {
            Text.send(sender, "player-only");
            return;
        }
        String options = String.join(", ", Arrays.stream(BossType.values()).map(BossType::id).toList());
        BossType type = args.length > 1 ? BossType.byId(args[1]) : null;
        if (type == null) {
            Text.send(sender, "unknown-boss", Text.ph("options", options));
            return;
        }
        if (plugin.sessions().summonBoss(player, type) == null) Text.send(sender, "not-in-session");
    }

    /** /trials rank <player> <rank> - set a player's Trial Rank. */
    private void rank(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) return;
        if (args.length < 3) {
            Text.send(sender, "rank-usage");
            return;
        }
        org.bukkit.OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            Text.send(sender, "unknown-player", Text.ph("name", args[1]));
            return;
        }
        try {
            int value = Math.max(0, Math.min(plugin.settings().maxRank, Integer.parseInt(args[2])));
            plugin.stats().setRank(target.getUniqueId(), target.getName(), value);
            plugin.stats().saveAsync();
            Text.send(sender, "rank-set", Text.ph("name", args[1]), Text.ph("rank", value <= 0 ? "-" : Text.roman(value)),
                    Text.ph("stars", RankBadge.component(value)));
        } catch (NumberFormatException e) {
            Text.send(sender, "rank-usage");
        }
    }

    private void warden(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) return;
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        GuardManager guards = plugin.guards();
        if (action.equals("list")) {
            if (guards.all().isEmpty()) Text.send(sender, "warden-none");
            for (Warden warden : guards.all()) {
                Location post = warden.post();
                Text.send(sender, "warden-list-entry", Text.ph("name", warden.name()),
                        Text.ph("state", warden.isOpen() ? "open for " + Text.duration(warden.openUntil() - System.currentTimeMillis())
                                : warden.isAlive() ? "guarding" : "waiting to spawn"),
                        Text.ph("points", warden.waypoints().size()),
                        Text.ph("location", post.getWorld().getName() + " " + post.getBlockX() + " " + post.getBlockY() + " " + post.getBlockZ()));
            }
            return;
        }
        if (args.length < 3) {
            Text.send(sender, "warden-usage");
            return;
        }
        String name = args[2];
        if (action.equals("create")) {
            if (!(sender instanceof Player player)) {
                Text.send(sender, "player-only");
                return;
            }
            guards.create(name, player.getLocation());
            Text.send(sender, "warden-created", Text.ph("name", name), Text.ph("radius", (int) plugin.settings().guardRadius));
            return;
        }
        Warden warden = guards.get(name);
        if (warden == null) {
            Text.send(sender, "warden-unknown", Text.ph("name", name));
            return;
        }
        switch (action) {
            case "point" -> {
                if (!(sender instanceof Player player)) {
                    Text.send(sender, "player-only");
                    return;
                }
                warden.waypoints().add(player.getLocation().getBlock().getLocation().add(0.5, 0, 0.5));
                guards.save();
                Text.send(sender, "warden-point", Text.ph("name", warden.name()), Text.ph("points", warden.waypoints().size()));
            }
            case "clearpoints" -> {
                warden.waypoints().clear();
                warden.waypoints().add(warden.post());
                guards.save();
                Text.send(sender, "warden-point", Text.ph("name", warden.name()), Text.ph("points", 1));
            }
            case "respawn" -> {
                guards.respawn(warden);
                Text.send(sender, "warden-respawned", Text.ph("name", warden.name()));
            }
            case "open" -> {
                guards.open(warden);
                Text.send(sender, "warden-opened", Text.ph("name", warden.name()));
            }
            case "remove" -> {
                guards.remove(warden.name());
                Text.send(sender, "warden-removed", Text.ph("name", warden.name()));
            }
            default -> Text.send(sender, "warden-usage");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("info", "stats", "top"));
            if (sender.hasPermission(ADMIN)) options.addAll(List.of("boss", "warden", "rank", "end", "reload"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "top" -> Arrays.stream(StatsStore.Category.values()).forEach(c -> options.add(c.name().toLowerCase(Locale.ROOT)));
                case "boss" -> Arrays.stream(BossType.values()).forEach(b -> options.add(b.id()));
                case "stats", "rank" -> plugin.getServer().getOnlinePlayers().forEach(p -> options.add(p.getName()));
                case "warden" -> options.addAll(List.of("create", "point", "clearpoints", "respawn", "open", "remove", "list"));
                default -> {
                }
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("warden") && !args[1].equalsIgnoreCase("create")) {
            plugin.guards().all().forEach(w -> options.add(w.name()));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
