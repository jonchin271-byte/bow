package gg.gnomeheist;

import gg.gnomeheist.gen.Sheets;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dev-only scripted heist, enabled with -Dgnomeheist.selftest=true (never set in the shipped game).
 * It plays the systems sheet end to end through the same code paths as a player and logs
 * "SELFTEST ..." lines: PASS/FAIL checks and SHOT markers for screenshots.
 */
final class SelfTest {
    static final boolean ENABLED = Boolean.getBoolean("gnomeheist.selftest");
    private record Step(int at, Runnable run) {}

    private static UUID playerId;
    private static int clock = -1;
    private static final List<Step> steps = new ArrayList<>();
    private static int failures;

    static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            playerId = handler.getPlayer().getUUID();
            clock = 0;
            GnomeHeist.LOG.info("SELFTEST armed");
        });
        ServerTickEvents.END_SERVER_TICK.register(SelfTest::tick);
    }

    static void check(boolean ok, String what) {
        if (!ok) failures++;
        GnomeHeist.LOG.info("SELFTEST {} {}", ok ? "PASS" : "FAIL", what);
    }

    static void shot(String name) {
        GnomeHeist.LOG.info("SELFTEST SHOT {}", name);
    }

    private static void tick(MinecraftServer server) {
        if (clock < 0) return;
        ServerPlayer p = server.getPlayerList().getPlayer(playerId);
        if (p == null) return;
        if (clock == 0) plan(server, p);
        clock++;
        for (Step s : steps) if (s.at == clock) s.run.run();
        if (clock == fireCheckAt) {
            Heist h = heist(p);
            boolean stunnedByHero = h.stunned.keySet().stream().anyMatch(id -> h.guards.get(id) == Sheets.GUARDS.get(0));
            check(stunnedByHero, "Soldier: 76's rifle stunned " + Sheets.GUARDS.get(0).name());
            shot("stun");
        }
    }

    private static Heist heist(ServerPlayer p) {
        return HeistEvents.HEISTS.get(p.getUUID());
    }

    private static void plan(MinecraftServer server, ServerPlayer p) {
        steps.clear();
        int countdown = Sheets.intParam(Sheets.SYSTEM_START, "countdownSeconds") * 20;
        int t = 300; // leaves time to pick a hero in HeroCraft's picker first
        steps.add(new Step(t, () -> {
            run(server, "time set day");
            run(server, "gamerule doDaylightCycle false");
            run(server, "gamerule doMobSpawning false");
            run(server, "difficulty normal");
            // Run /heist as the player, like a player would.
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "heist");
            Heist h = heist(p);
            check(h != null, "heist started");
            check(h != null && h.lootBlocks.size() + h.lootStatues.size() == Sheets.LOOT.size(), "all loot placed");
            check(h != null && h.guards.size() == Sheets.GUARDS.size(), "all guards spawned");
        }));
        steps.add(new Step(t + 10, () -> look(p, heist(p), -13.5, 4, -4.5, 2, 2, 7)));
        steps.add(new Step(t + 30, () -> shot("outside")));
        t += countdown + 5;
        steps.add(new Step(t, () -> {
            Heist h = heist(p);
            check(h.running(), "timer running after countdown");
            // Freeze the guards for the walkthrough shots; they are released again before the catch test.
            for (UUID id : h.guards.keySet()) if (h.level.getEntity(id) instanceof Mob m) m.setNoAi(true);
            look(p, h, 1.5, 1, 7.5, 6, 1.5, 7.5);
        }));
        steps.add(new Step(t += 20, () -> shot("hall")));
        // Grab with a real key press, like a player holding a hero weapon (HeroCraft owns the mouse).
        steps.add(new Step(t += 10, () -> {
            look(p, heist(p), 3.6, 1, 2.5, 2.5, 0.5, 2.5);
            GnomeHeist.LOG.info("SELFTEST CROUCH");
        }));
        steps.add(new Step(t += 40, () -> {
            look(p, heist(p), 6.5, 1, 11.4, 6.5, 0.5, 12.5);
            GnomeHeist.LOG.info("SELFTEST CROUCH");
        }));
        steps.add(new Step(t += 40, () -> {
            Heist h = heist(p);
            check(h.carried.size() == 2 && Heist.total(h.carried) == 60, "crouch-grabbed cake + lantern = 60 (real Shift key)");
        }));
        steps.add(new Step(t += 20, () -> {
            Heist h = heist(p);
            look(p, h, -5.5, 1, 7.5, -14, 2.5, 7.5);
        }));
        steps.add(new Step(t += 5, () -> {
            Heist h = heist(p);
            check(h.carried.isEmpty() && Heist.total(h.banked) == 60, "banked 60 at the van");
        }));
        steps.add(new Step(t += 15, () -> shot("van")));
        steps.add(new Step(t += 20, () -> {
            Heist h = heist(p);
            grab(p, h, "gold");
            look(p, h, 18.5, 1, 8.6, 18.0, 2.2, 12.5);
        }));
        steps.add(new Step(t += 25, () -> shot("vault")));
        steps.add(new Step(t += 10, () -> {
            Heist h = heist(p);
            grab(p, h, "gnome");
            grab(p, h, "jewels");
            check(h.carried.size() == 3, "carrying gnome + gold + jewels");
            int before = h.lootBlocks.size();
            BlockPos books = locate(h, "books");
            h.tryGrabBlock(p, books);
            check(h.carried.size() == 3 && h.lootBlocks.size() == before, "carry limit 3 holds");
        }));
        steps.add(new Step(t += 10, () -> {
            Heist h = heist(p);
            // Release the guards and let the Head of Security catch the player in the vault.
            for (UUID id : h.guards.keySet()) {
                if (h.level.getEntity(id) instanceof Mob m) {
                    m.setNoAi(false);
                    m.setTarget(p);
                }
            }
        }));
        steps.add(new Step(t += 15, () -> shot("chase")));
        // Up to 15 s for a guard to land a hit.
        for (int i = 1; i <= 15; i++) {
            final boolean last = i == 15;
            steps.add(new Step(t + i * 20, () -> {
                Heist h = heist(p);
                if (caughtChecked) return;
                if (h.carried.isEmpty() || last) {
                    caughtChecked = true;
                    check(h.carried.isEmpty() && Heist.total(h.banked) == 60, "caught: carried loot lost, banked kept");
                    boolean held = h.guards.keySet().stream().allMatch(id -> h.level.getEntity(id) instanceof Mob m && m.isNoAi());
                    check(held && h.graceTicks > 0, "caught: guards back at their posts, holding for the head start");
                    Sheets.Guard w = Sheets.GUARDS.get(0);
                    look(p, h, -5.5, 1, 7.5, w.x() + 0.5, w.y() + 1.0, w.z() + 0.5);
                    GnomeHeist.LOG.info("SELFTEST FIRE");
                    fireCheckAt = clock + 90;
                }
            }));
        }
        t += 16 * 20;
        steps.add(new Step(t, () -> {
            Heist h = heist(p);
            // Stun: hit a guard hard enough to kill it; it must survive, frozen.
            Entity g = h.level.getEntity(h.guards.keySet().iterator().next());
            if (g instanceof Mob m) {
                m.hurt(p.damageSources().playerAttack(p), 1000f);
                check(m.isAlive() && m.isNoAi() && h.stunned.containsKey(m.getUUID()), "guard stunned instead of killed");
            }
        }));
        steps.add(new Step(t += 20, () -> {
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "heist stop");
            check(heist(p) == null, "heist stopped and score card shown");
        }));
        steps.add(new Step(t += 30, () -> shot("score")));
        steps.add(new Step(t += 60, () -> {
            GnomeHeist.LOG.info("SELFTEST DONE failures={}", failures);
        }));
    }

    private static boolean caughtChecked;
    private static int fireCheckAt = -1;

    private static void run(MinecraftServer server, String cmd) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
    }

    private static BlockPos locate(Heist h, String lootId) {
        Sheets.Loot l = Sheets.LOOT.stream().filter(x -> x.id().equals(lootId)).findFirst().orElseThrow();
        return h.at(l.x(), l.y(), l.z());
    }

    private static void grab(ServerPlayer p, Heist h, String lootId) {
        Sheets.Loot l = Sheets.LOOT.stream().filter(x -> x.id().equals(lootId)).findFirst().orElseThrow();
        if (l.kind().equals("statue")) {
            for (UUID id : List.copyOf(h.lootStatues.keySet())) {
                Entity e = h.level.getEntity(id);
                if (e != null) h.tryGrabStatue(p, e);
            }
        } else {
            h.tryGrabBlock(p, locate(h, lootId));
        }
    }

    /** Put the player at a local position looking at another local position. */
    private static void look(ServerPlayer p, Heist h, double x, double y, double z, double tx, double ty, double tz) {
        double wx = h.origin.getX() + x, wy = h.origin.getY() + y, wz = h.origin.getZ() + z;
        double dx = tx - x, dy = ty - y - 1.62, dz = tz - z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.teleportTo(h.level, wx, wy, wz, yaw, pitch);
    }
}
