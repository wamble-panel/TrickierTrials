package de.t14d3.trickiertrials.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** The star badge and tier name for each Trial Rank, configured under rank-badges / rank-tiers. */
public final class RankBadge {

    /** Reads {@code &#RRGGBB} hex colours and classic {@code &} codes. */
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    /** Writes {@code §x§R§R§G§G§B§B}, which scoreboards, TAB and chat plugins understand. */
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static List<String> badges = List.of();
    private static List<String> tiers = List.of();

    private RankBadge() {
    }

    public static void load(FileConfiguration config) {
        badges = config.getStringList("rank-badges");
        tiers = config.getStringList("rank-tiers");
    }

    private static String pick(List<String> list, int rank) {
        if (list.isEmpty()) return "";
        return list.get(Math.max(0, Math.min(rank, list.size() - 1)));
    }

    /** The badge exactly as written in the config, e.g. {@code &#C0C0C0★&#CD7F32★★}. */
    public static String raw(int rank) {
        return pick(badges, rank);
    }

    public static Component component(int rank) {
        return AMPERSAND.deserialize(raw(rank)).decoration(TextDecoration.ITALIC, false);
    }

    /** The badge with section-sign colours, for placeholders. */
    public static String legacy(int rank) {
        return SECTION.serialize(AMPERSAND.deserialize(raw(rank)));
    }

    public static String tier(int rank) {
        return pick(tiers, rank);
    }
}
