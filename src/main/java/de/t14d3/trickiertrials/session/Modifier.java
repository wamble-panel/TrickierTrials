package de.t14d3.trickiertrials.session;

import java.util.Locale;

/**
 * Random twists rolled at the start of every trial (inspired by Hades' Pact of Punishment and Diablo rifts),
 * so no two runs of the same chamber play the same. Harder modifiers pay out more score.
 */
public enum Modifier {

    BLOODLUST("Bloodlust", "#FF5E6C", "Foes deal 25% more damage", 1.25),
    FORTIFIED("Fortified", "#F7C873", "Foes have 40% more health", 1.20),
    FRENZY("Frenzy", "#9AD8FF", "Foes move 20% faster", 1.15),
    ELITE_HUNT("Elite Hunt", "#E8875B", "Elites appear far more often", 1.20),
    GALE("Gale", "#5CC8B5", "Every hit you take launches you upward", 1.15),
    LEECHING("Leeching", "#D64575", "Foes heal from the damage they deal", 1.15),
    GLASS_CANNON("Glass Cannon", "#FFB347", "You deal and take 35% more damage", 1.10),
    DARKNESS("Darkness", "#A974FF", "The lights of the chamber falter now and then", 1.15),
    FAMINE("Famine", "#9A9AA6", "Clearing a wave no longer heals you", 1.25),
    UNSTABLE("Unstable", "#FF8A3D", "Slain foes may explode", 1.15),
    MOMENTUM("Momentum", "#7EE081", "Combos last longer and climb higher", 1.0),
    GOLDEN("Golden Trial", "#FFD86B", "Double Trial Key rewards, but foes have 25% more health", 1.0);

    private final String displayName;
    private final String color;
    private final String description;
    private final double scoreMultiplier;

    Modifier(String displayName, String color, String description, double scoreMultiplier) {
        this.displayName = displayName;
        this.color = color;
        this.description = description;
        this.scoreMultiplier = scoreMultiplier;
    }

    public String displayName() {
        return displayName;
    }

    public String color() {
        return color;
    }

    public String description() {
        return description;
    }

    public double scoreMultiplier() {
        return scoreMultiplier;
    }

    /** MiniMessage for the modifier name with its description on hover. */
    public String mini() {
        return "<hover:show_text:'<" + color + ">" + displayName + "</" + color + "><newline><gray>" + description + "'><" + color + ">"
                + displayName + "</" + color + "></hover>";
    }

    public static Modifier byName(String name) {
        for (Modifier modifier : values()) if (modifier.name().equalsIgnoreCase(name.replace('-', '_').toUpperCase(Locale.ROOT))) return modifier;
        return null;
    }
}
