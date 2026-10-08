package de.t14d3.trickiertrials.boss;

import de.t14d3.trickiertrials.TrickierTrials;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Collection;

/** The encounter a boss belongs to. */
public interface BossHost {

    TrickierTrials plugin();

    /** Players currently fighting in the encounter. */
    Collection<Player> players();

    /** Registers a mob summoned by the boss so it counts as part of the encounter. */
    void registerMinion(LivingEntity minion);

    void onBossEnrage(TrialBoss boss);
}
