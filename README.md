# TrickierTrials

Turns Minecraft's Trial Chambers into a wave-based arena with bosses, elite mobs, combos and leaderboards.
Built for **Paper 26.2** (Java 25).

## Features

### Trial encounters
An encounter starts as soon as a trial spawner spawns its first mob. In a generated chamber the arena is
the whole structure. For a custom-built chamber, it is the area around the spawner (`session.fallback-radius`).

- **Waves.** Kill trial mobs to fill the wave bar. Each wave needs more kills and makes mobs stronger.
  Clearing a wave heals everyone, pays out bonus points and starts a short break.
- **Reinforcements.** Vanilla spawners run out of mobs. When that happens, the chamber spawns
  more mobs so a wave can always finish.
- **Boss waves.** Every 3rd wave (configurable) is a boss fight. A boss uses telegraphed abilities,
  summons minions at 66% and 33% health, and enrages at low health.
- **Victory.** Beating the final wave (default 9) conquers the chamber. Players get fireworks,
  victory loot and a server-wide broadcast, and the chamber then goes on cooldown.
- **Player-count scaling.** Mob health and damage, kills per wave, boss health and boss loot all
  scale with the number of players in the encounter.
- **Ominous chambers.** Ominous spawners make bosses stronger and elites more common.

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
| `/trials top [score\|wave\|kills\|bosses\|victories]` | Shows the leaderboard | – |
| `/trials boss <type>` | Summons a boss in your encounter, or starts an encounter if you're not in one | `trickiertrials.admin` |
| `/trials end` | Ends your encounter | `trickiertrials.admin` |
| `/trials reload` | Reloads the config | `trickiertrials.admin` |

The command can also be typed as `/trickiertrials` or `/tt`.

## Configuration
Everything is in `config.yml`, including every message (MiniMessage) and the colour palette.
Palette colours work as tags, e.g. `<copper>text</copper>`, and inside gradients, e.g.
`<gradient:copper:gold>text</gradient>`.

Default palette: copper `#E8875B`, gold `#F7C873`, teal `#5CC8B5`, sky `#9AD8FF`, ominous `#A974FF`,
danger `#FF5E6C`, success `#7EE081`, muted `#9A9AA6`, light `#F3EEE7`.

A config from v1 is migrated automatically. The old file is kept as `config-v1.yml`.

## Building
```
./gradlew build
```
Requires JDK 25. The jar is written to `build/libs/`.
