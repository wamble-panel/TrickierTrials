package de.t14d3.trickiertrials.session;

import java.util.UUID;

/** A single player's performance inside one encounter. */
public final class PlayerRun {

    public final UUID uuid;
    public String name;
    public long score;
    public int kills;
    public int elites;
    public int bosses;
    public int deaths;
    public int combo;
    public int bestCombo;
    public long lastKillTick;

    PlayerRun(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public double multiplier(double perKill, double max) {
        return Math.min(max, 1 + perKill * Math.max(0, combo - 1));
    }
}
