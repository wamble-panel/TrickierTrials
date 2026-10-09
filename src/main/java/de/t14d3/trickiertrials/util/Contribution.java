package de.t14d3.trickiertrials.util;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Decides who actually helped in a fight, based on the damage each player dealt. */
public final class Contribution {

    private Contribution() {
    }

    /**
     * Players whose damage is at least {@code minShare} of the average damage per player.
     * {@code players} is how many players took part (present in the fight), so standing around AFK lowers
     * nobody's bar but never earns a reward either.
     */
    public static Set<UUID> contributors(Map<UUID, Double> damage, int players, double minShare) {
        Set<UUID> result = new HashSet<>();
        double total = 0;
        for (double value : damage.values()) total += value;
        if (total <= 0) return result;
        double needed = minShare * total / Math.max(1, players);
        for (Map.Entry<UUID, Double> entry : damage.entrySet()) {
            if (entry.getValue() > 0 && entry.getValue() >= needed) result.add(entry.getKey());
        }
        return result;
    }
}
