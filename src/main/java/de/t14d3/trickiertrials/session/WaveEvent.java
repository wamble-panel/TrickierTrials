package de.t14d3.trickiertrials.session;

/** Special waves that randomly replace a normal one. */
public enum WaveEvent {

    /** Almost every foe is an elite. */
    ELITE_SURGE("Elite Surge", "#E8875B", "Elites everywhere - +50% score"),
    /** Many more, but weaker and faster, foes. */
    SWARM("Swarm", "#7EE081", "A horde of weaker, faster foes"),
    /** Clear the wave before the timer runs out for a Trial Key. */
    BLITZ("Blitz", "#9AD8FF", "Clear the wave in time for a Trial Key"),
    /** A Treasure Breeze appears - kill it before it escapes. */
    TREASURE("Treasure Breeze", "#FFD86B", "Catch the Treasure Breeze before it escapes!");

    private final String displayName;
    private final String color;
    private final String description;

    WaveEvent(String displayName, String color, String description) {
        this.displayName = displayName;
        this.color = color;
        this.description = description;
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
}
