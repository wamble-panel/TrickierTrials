package de.t14d3.trickiertrials.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MiniMessage based text helper. Every palette colour from the config is exposed as its own tag
 * (e.g. {@code <copper>}) and can also be used inside gradients ({@code <gradient:copper:gold>}).
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern GRADIENT = Pattern.compile("<(gradient|transition)((?::[^>]+)+)>");
    private static final String[] ROMAN = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};

    private static final Map<String, String> DEFAULT_PALETTE = new LinkedHashMap<>();

    static {
        DEFAULT_PALETTE.put("copper", "#E8875B");
        DEFAULT_PALETTE.put("gold", "#F7C873");
        DEFAULT_PALETTE.put("teal", "#5CC8B5");
        DEFAULT_PALETTE.put("sky", "#9AD8FF");
        DEFAULT_PALETTE.put("ominous", "#A974FF");
        DEFAULT_PALETTE.put("danger", "#FF5E6C");
        DEFAULT_PALETTE.put("success", "#7EE081");
        DEFAULT_PALETTE.put("muted", "#9A9AA6");
        DEFAULT_PALETTE.put("light", "#F3EEE7");
    }

    private static Map<String, String> palette = new LinkedHashMap<>(DEFAULT_PALETTE);
    private static TagResolver paletteResolver = buildResolver();
    private static ConfigurationSection messages;
    private static String prefix = "";

    private Text() {
    }

    public static void load(FileConfiguration config) {
        palette = new LinkedHashMap<>(DEFAULT_PALETTE);
        ConfigurationSection section = config.getConfigurationSection("palette");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key, "");
                if (TextColor.fromHexString(value) != null) {
                    palette.put(key.toLowerCase(Locale.ROOT), value);
                }
            }
        }
        paletteResolver = buildResolver();
        messages = config.getConfigurationSection("messages");
        prefix = raw("prefix");
    }

    private static TagResolver buildResolver() {
        TagResolver.Builder builder = TagResolver.builder();
        palette.forEach((name, hex) -> builder.resolver(Placeholder.styling(name, TextColor.fromHexString(hex))));
        return builder.build();
    }

    public static TextColor color(String name) {
        TextColor color = TextColor.fromHexString(palette.getOrDefault(name, "#FFFFFF"));
        return color == null ? TextColor.color(0xFFFFFF) : color;
    }

    /** Replaces palette names inside gradient / transition arguments with their hex values. */
    private static String expandGradients(String input) {
        if (input.indexOf('<') < 0) return input;
        Matcher matcher = GRADIENT.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            StringBuilder args = new StringBuilder();
            for (String arg : matcher.group(2).substring(1).split(":")) {
                args.append(':').append(palette.getOrDefault(arg.toLowerCase(Locale.ROOT), arg));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement("<" + matcher.group(1) + args + ">"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static Component parse(String input, TagResolver... resolvers) {
        if (input == null || input.isEmpty()) return Component.empty();
        TagResolver all = TagResolver.builder().resolver(paletteResolver).resolvers(resolvers).build();
        return MM.deserialize(expandGradients(input), all).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static String raw(String key) {
        // No explicit default here, so values missing from an older config fall back to the bundled config.yml.
        String value = messages == null ? null : messages.getString(key);
        return value == null ? "" : value;
    }

    public static Component msg(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    public static Component prefixed(String key, TagResolver... resolvers) {
        return parse(prefix + raw(key), resolvers);
    }

    public static void send(Audience audience, String key, TagResolver... resolvers) {
        if (raw(key).isEmpty()) return;
        audience.sendMessage(prefixed(key, resolvers));
    }

    public static void title(Audience audience, String titleKey, String subtitleKey, int fadeIn, int stay, int fadeOut, TagResolver... resolvers) {
        audience.showTitle(Title.title(msg(titleKey, resolvers), msg(subtitleKey, resolvers),
                Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    public static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver ph(String key, Component value) {
        return Placeholder.component(key, value);
    }

    /** One formatter per thread: reused (it is called many times per second) and safe for async placeholder requests. */
    private static final ThreadLocal<NumberFormat> NUMBER = ThreadLocal.withInitial(() -> NumberFormat.getIntegerInstance(Locale.US));

    public static String number(long value) {
        return NUMBER.get().format(value);
    }

    public static String roman(int number) {
        if (number <= 0 || number >= 4000) return String.valueOf(number);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ROMAN.length; i++) {
            while (number >= ROMAN_VALUES[i]) {
                number -= ROMAN_VALUES[i];
                sb.append(ROMAN[i]);
            }
        }
        return sb.toString();
    }

    public static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** A small text based progress bar, e.g. ▰▰▰▱▱. */
    public static Component bar(double progress, int length, TextColor filled, TextColor empty) {
        int full = (int) Math.round(Math.max(0, Math.min(1, progress)) * length);
        return Component.text("▰".repeat(full), filled).append(Component.text("▱".repeat(length - full), empty));
    }
}
