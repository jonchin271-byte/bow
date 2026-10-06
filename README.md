# Gnome Heist (a HeroCraft remix)

A 3-minute loot run for Minecraft, played as an Overwatch-style hero from HeroCraft, with a
Burglin' Gnomes-style break-in.

Type `/heist` and a house with a getaway van appears around you. Grab loot, run it back to the
van before the timer runs out, and stay out of the guards' reach. Only loot you bank at the van
counts.

## What you get
- **`/heist`** builds a four-room house (hall, lounge, study, vault) and a getaway van next to you.
- **10 pieces of loot** worth 20 to 500 points. The jackpot is the **Golden Garden Gnome** in the vault.
- **Grab** loot by crouching (**Shift**) right next to it. You can carry **3 things at once**.
  HeroCraft uses both mouse buttons for hero weapons, so clicks only grab when your hand is empty.
- **Bank** loot by standing on the yellow drop zone behind the van.
- **3 guards**: the Night Watchman, the Butler and the Head of Security. If one hits you, you're
  **caught**: you lose what you're carrying and go back to the van. Banked loot is safe.
- **Hero powers** from HeroCraft (Soldier: 76, Doomfist, D.Va) **stun** guards for a few seconds.
  Guards are never killed.
- **3-minute timer** in a boss bar that turns red for the last 30 seconds.
- **Score card** at the end: everything you banked, out of 1120 possible.
- `/heist stop` ends a heist early. No cheats needed.
- Single player.

## Getting started (Melty)
1. Press **Play** in Melty. It sets up its own Minecraft 1.21.1 (Prism Launcher, Fabric, Fabric API)
   with HeroCraft and Gnome Heist.
2. The first time, Prism asks you to sign in with the Microsoft account that owns Minecraft: Java Edition.
3. Create a new single-player world (Superflat is ideal), pick a hero, then type `/heist`.

`/heist` clears an area of about 40 × 20 blocks around you to build the house, so don't start a heist
next to builds you want to keep.

## How it's made
- `sheets/*.json` is the design: rooms, places, loot, guards, systems and the Fabric hooks they use.
  These sheets are the source of truth.
- `python3 tools/sheets.py gen` checks every cell and cross-reference (the preflight), then generates
  `mod/src/main/java/gg/gnomeheist/gen/Sheets.java`.
- `mod/` is a Fabric mod for Minecraft 1.21.1. To build it, run `./gradlew build` (Gradle runs on JDK 25;
  the mod targets Java 21).
- `./gradlew runClient -Dgnomeheist.selftest=true` plays a scripted heist and logs `SELFTEST PASS/FAIL`.
  This only happens in dev runs; the shipped mod never enables it.

## Credits and licenses
- **Gnome Heist** by jonchin271, under CC BY-NC 4.0.
- **HeroCraft** by TRS, under CC BY-NC 4.0 (https://creativecommons.org/licenses/by-nc/4.0/). Shipped unchanged.
- **Prism Launcher**: GPL-3.0, https://github.com/PrismLauncher/PrismLauncher (from HeroCraft's bundle).
- **Fabric API**: Apache-2.0, https://github.com/FabricMC/fabric.
- Inspired by Burglin' Gnomes and Overwatch 2. Not affiliated with or endorsed by their makers,
  Blizzard Entertainment, Mojang Studios or Microsoft. Overwatch and its heroes are trademarks of
  Blizzard Entertainment. No content from either game is included.
- Built with Claude Code.
