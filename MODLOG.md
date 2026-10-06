# MODLOG: Gnome Heist

## Versions
- Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17+1.21.1 (the same as HeroCraft's bundle).
- Fabric Loom 1.18.2 on Gradle 9.8.0. Loom 1.18 needs **JDK 25 to run Gradle**
  (set `org.gradle.java.home` in `~/.gradle/gradle.properties`). The mod still targets Java 21.
- Mojang official mappings.

## Route
- Overwatch 2 has anti-cheat and no loader Melty installs, so it was never touched.
- Burglin' Gnomes isn't in Melty's catalog.
- The playable base is Minecraft: Java, remixing HeroCraft (TRS, CC BY-NC 4.0, open for remixes on Melty).
- Gnome Heist is a separate Fabric mod that loads next to the unchanged HeroCraft jar.
- The release reuses HeroCraft's one-click Prism Launcher bundle; its instance is renamed `GnomeHeist`.

## Gotchas
1. HeroCraft's jar was built with Loom 1.18.2. Older Loom refuses to remap it ("Mod was built with a newer
   version of Loom").
2. `ArmorStand.setSmall` is private in 1.21.1. `readAdditionalSaveData(CompoundTag{Small:1b})` is public
   and works. Summoning the stand through a command with suppressed output failed silently.
3. A guard catching the player at the van kept re-catching them. Fix: on a catch, every guard returns to
   its post and holds for `graceTicks`.
4. Survival mining of loot blocks is slow, so grabbing hooks `AttackBlockCallback`: the first punch
   grabs the loot.
5. HeroCraft's hero picker opens on join. The test driver clicks "Play Soldier: 76" with xdotool.

## Test oracle
- `./gradlew runClient -Dgnomeheist.selftest=true` in Xvfb, using a superflat world generated with
  `runServer`.
- It logs `SELFTEST PASS/FAIL` lines and `SELFTEST SHOT` markers. A watcher script takes the screenshots
  and clicks the mouse for the rifle test.
