# TrickierTrials

Turns Minecraft's Trial Chambers into a wave-based arena with bosses, elite mobs, combos and leaderboards.
Built for **Paper 26.2** (Java 25).

## Features

### Trial encounters
An encounter starts as soon as a trial spawner spawns its first mob. In a generated chamber the arena is
the whole structure. For a custom-built chamber, it is the area around the spawner (`session.fallback-radius`).

- **Waves.** Kill trial mobs to fill the wave bar. Each wave needs more kills and makes mobs stronger.
  Clearing a wave heals everyone, pays out bonus points and starts a short break.
- **Real trial spawners.** Each wave is fought against the chamber's own trial spawners. At the
  start of a wave, and whenever the fight runs dry, spawners on cooldown are woken up. They then
  detect players, open their shutters and spawn mobs exactly as in vanilla. Their trial-key loot
  drops only once per spawner per encounter, so waking them can't be farmed.
- **Fallback spawns.** If no spawner near the players can be woken, or a wave is stuck, trial mobs
  appear at the nearest spawner so the wave can always finish.
- **Boss waves.** Every 3rd wave (configurable) is a boss fight. A boss uses telegraphed abilities,
  summons minions at 66% and 33% health, and enrages at low health.
- **Victory.** Beating the final wave (default 9) conquers the chamber. Players get fireworks,
  a Trial Key each and a server-wide broadcast, and the chamber then goes on cooldown.
- **Rewards are regular Trial Keys.** Bosses, victories and the Warden give Trial Keys, so the
  chamber's own vaults stay the real prize.
- **Player-count scaling.** Mob health and damage, kills per wave, boss health and boss loot all
  scale with the number of players in the encounter.
- **Ominous chambers.** Ominous spawners make bosses stronger and elites more common.

### Built for replaying
- **Trial Rank (0-20), built to be a long grind.** Conquering chambers earns progress towards the next
  rank:
  - **Wins needed rise with rank.** Ranks 1-3 need 2 wins each, rising to 12 wins for rank 20, for
    121 conquered chambers in total.
  - **A win only counts if** the trial was at your own rank or higher, you died at most once, and you
    were there by wave 2.
  - **A failed trial costs 1 win of progress.** You never lose a whole rank.
  - **Players always see their progress**, e.g. "3/5 wins", and why a win didn't count.

  A group plays at its members' average rank. Every rank adds mob health, damage and elite chance,
  plus boss health. From rank 5, bosses gain elite affixes, two from rank 10. In return, victories give
  more Trial Keys and more score. All of these rules are configurable under `progression`.
- **Run modifiers.** Every trial rolls modifiers, starting at 1 and gaining one per 3 ranks, up to 4.
  The pool is Bloodlust, Fortified, Frenzy, Elite Hunt, Gale, Leeching, Glass Cannon, Darkness,
  Famine, Unstable, Momentum and Golden Trial (double keys). Harder modifiers give more score.
  Hover over a modifier in chat to see what it does.
- **Wave events.** From wave 2, a normal wave has a 35% chance to become a special wave:
  - **Elite Surge**: elites everywhere, +50% score.
  - **Swarm**: 1.5x more foes that are weaker and faster.
  - **Blitz**: beat the timer for a Trial Key.
  - **Treasure Breeze**: kill the fleeing breeze for Trial Keys.
- **Large groups.** Everything scales with player count. A boss whose health would pass Minecraft's
  1024 cap takes reduced damage instead, so it stays a real fight for 15+ players. Boss minions are
  capped to protect server performance.

### Only trial chamber mobs
Everything the plugin spawns is a mob that vanilla trial spawners also spawn: Breeze, Bogged,
Zombie, Husk, Skeleton, Stray, Spider, Cave Spider, Slime and Silverfish. Reinforcements copy the
mobs of the chamber's own spawners. Bosses are empowered versions of these mobs. The list is
`trial-mobs.mob-pool` in the config.

### Chamber Warden
A guard boss patrols your corridor. While it lives, every trial spawner and vault within
`warden.guard-radius` (default 64 blocks) of its post is **sealed**: spawners spawn nothing, no
trial can start and vaults won't open. Kill the Warden and all of those chambers open. Everyone who
helped gets a Trial Key, and players can go to whichever chamber they want. After
`warden.unseal-duration` (default 20 minutes) the Warden comes back by itself and seals them again.
Trials that are already running are not interrupted. The Warden goes back to its route when nobody
is near, heals slowly, and never strays further than `leash-range` from its post.

Setup, done once in-game:
1. Stand in the corridor where the guard should stand and run `/trials warden create main`.
2. Walk the route and run `/trials warden point main` at each turn. The Warden walks these
   points back and forth.

Nothing needs to be started by hand. The Warden spawns, patrols and respawns on its own, and the
open/sealed state survives restarts.

### Bosses
| Boss | Mob | Abilities |
|------|-----|-----------|
| Tempest, the Howling Gale | Breeze | Wind-charge volleys, Gale Burst knock-back nova |
| The Bog Sovereign | Bogged | Telegraphed poison arrow rain, toxic spore clouds |
| Copperclad Juggernaut | Zombie | Seismic Slam (jump + shockwave), charges the farthest player |
| Vexa, the Brood Mother | Spider | Web snares at players' feet, summons cave-spider broods, poison bite |
| The Rimeborn Marksman | Stray | Frost Nova (slow + freeze), blinks behind players |

### Elite mobs
Elite mobs (Diablo-style affixes) appear more often in later waves and can roll up to 3 affixes:
Swift, Brutal, Vampiric, Molten, Frostbite, Venomous, Volatile, Shielded, Galeborn, Splitting and
Undying. Elites drop bonus XP and sometimes a Trial Key.

### HUD
- **Boss bar** showing wave progress, the countdown between waves, and a separate health bar for the boss.
- **Action bar** with your wave, score, combo meter and elapsed time.
- **Titles and sounds** for wave starts, wave clears, boss spawns, combo milestones and victories.
- **End-of-run summary** listing score, kills, elites, bosses, best combo, deaths and the MVP.

### Score and combos
Every kill scores points. Kills in quick succession build a combo multiplier, up to 3x by default.
Dying costs points.

### Records
`stats.yml` keeps lifetime records for each player: best score, highest wave, kills, elites, bosses,
conquered chambers and best combo. `/trials top` shows the leaderboard.

### Chamber protection (from v1)
- Broken chamber blocks regrow and drop nothing.
- Blocks placed by players decay again.
- Mining chamber blocks gives Mining Fatigue.
- Trial mobs can only drop the materials you allow.

### Vault reset
Players can open the same vault again once a cooldown has passed (default 24 hours). This now uses
the Paper API and no longer needs server internals.

## Commands
| Command | Description | Permission |
|---------|-------------|------------|
| `/trials info` | Shows your current encounter | – |
| `/trials stats [player]` | Shows personal records | – |
| `/trials weekly` | This week's leaderboard, your position and time left | – |
| `/trials top [score\|wave\|kills\|bosses\|victories\|rank]` | Shows the leaderboard | – |
| `/trials boss <type>` | Summons a boss in your encounter, or starts an encounter if you're not in one | `trickiertrials.admin` |
| `/trials warden create <name>` | Creates a Warden with its post at your position | `trickiertrials.admin` |
| `/trials warden point <name>` | Adds your position as a patrol point | `trickiertrials.admin` |
| `/trials warden clearpoints <name>` | Resets the patrol route | `trickiertrials.admin` |
| `/trials warden respawn <name>` | Brings the Warden back and seals the trials now | `trickiertrials.admin` |
| `/trials warden open <name>` | Opens the trials without a fight (for testing) | `trickiertrials.admin` |
| `/trials warden remove <name>` / `list` | Deletes a Warden or lists all Wardens | `trickiertrials.admin` |
| `/trials rank <player> <0-20>` | Sets a player's Trial Rank | `trickiertrials.admin` |
| `/trials hologram <weekly\|lastweek\|top <category>> [name]` | Creates a FancyHolograms leaderboard where you stand | `trickiertrials.admin` |
| `/trials end` | Ends your encounter | `trickiertrials.admin` |
| `/trials reload` | Reloads the config | `trickiertrials.admin` |

The command can also be typed as `/trickiertrials` or `/tt`.

## Weekly leaderboard
Every trial adds its score to the player's weekly total (`weekly.metric: best` counts only the best
single run instead). The board resets every week, by default on Monday at 00:00 UTC; set the day, time
and `timezone` under `weekly`. When a week ends:
- The top 3 players get their prizes. By default these are 6, 4 and 2 Trial Keys. Players who are
  offline receive them on their next join.
- Optional console `commands` run for each winner (`%player%`, `%place%`).
- The winners are announced and the final standings are kept as "last week".

`/trials weekly` shows the standings, your position and the time left. `/trials weekly reset`
(admin) ends the week immediately and pays out the prizes.

### Leaderboard holograms (FancyHolograms)
With FancyHolograms and PlaceholderAPI installed, stand where the board should appear and run:
- `/trials hologram weekly`: this week's top 10, the reset countdown and the viewer's own place
- `/trials hologram lastweek`: last week's champions
- `/trials hologram top <score|wave|kills|bosses|victories|rank>`: a lifetime top 10

Add a name at the end to choose the hologram's name, otherwise it is `trials_weekly` and so on. Use a name
that doesn't exist yet. The lines come from `holograms` in `config.yml`, and the hologram can be edited
afterwards with `/hologram edit <name>`.

## PlaceholderAPI
If [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) is installed, these
placeholders are available for TAB, scoreboards, chat formats and menus:

| Placeholder | Example | Description |
|---|---|---|
| `%trickiertrials_rank_stars%` | ★ (coloured) | Rank badge with colours |
| `%trickiertrials_rank_stars_hex%` | `&#FFD700✯` | Badge as written in the config, for plugins that read `&#RRGGBB` themselves |
| `%trickiertrials_rank%` | `7` | Rank number (0-20) |
| `%trickiertrials_rank_tier%` | `Gold` | Tier name |
| `%trickiertrials_rank_roman%` | `VII` | Rank in roman numerals |
| `%trickiertrials_progress%` / `%trickiertrials_progress_needed%` | `3` / `5` | Wins towards the next rank, and wins it needs |
| `%trickiertrials_weekly_name_<1-10>%` / `_weekly_score_<n>` / `_weekly_wins_<n>` | `kirito2524` / `12,840` / `3` | This week's leaderboard |
| `%trickiertrials_weekly_score%` / `%trickiertrials_weekly_position%` | `4,210` / `5` | The viewer's own weekly score and place |
| `%trickiertrials_weekly_reset%` | `2d 5h` | Time until the weekly reset |
| `%trickiertrials_lastweek_name_<n>%` / `_lastweek_score_<n>` | | Last week's final standings |
| `%trickiertrials_top_<score\|wave\|kills\|bosses\|victories\|rank>_name_<n>%` / `_value_<n>` | | Lifetime leaderboards |
| `%trickiertrials_best_score%`, `_best_wave`, `_kills`, `_bosses`, `_victories`, `_runs` | `12840` | Personal records |

All `rank` placeholders are empty for players without a rank. Each rank shows a single symbol. The
shape gives the tier and the brightness gives the step inside it (dim, normal, bright):
Bronze ✧ (1-3), Silver ✦ (4-6), Gold ✯ (7-9), Emerald ❖ (10-12), Diamond ◆ (13-15), Crystal ✵ (16-19,
pure white at 19) and ✪ for max rank 20. Every badge and tier name can be changed under
`rank-badges` and `rank-tiers` in `config.yml`.

## Configuration
Everything is in `config.yml`, including every message (MiniMessage) and the colour palette.
Palette colours work as tags, e.g. `<copper>text</copper>`, and inside gradients, e.g.
`<gradient:copper:gold>text</gradient>`.

Default palette: copper `#E8875B`, gold `#F7C873`, teal `#5CC8B5`, sky `#9AD8FF`, ominous `#A974FF`,
danger `#FF5E6C`, success `#7EE081`, muted `#9A9AA6`, light `#F3EEE7`.

A config from v1 is migrated automatically. The old file is kept as `config-v1.yml`.

## Building
GitHub Actions builds the plugin on every push. Download the jar from the run's **TrickierTrials**
artifact, or from the release created for each `v*` tag.

To build locally:
```
./gradlew build
```
Requires JDK 25. The jar is written to `build/libs/`.
