package gg.gnomeheist;

import com.mojang.brigadier.CommandDispatcher;
import gg.gnomeheist.gen.Sheets;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The hooks sheet: each row's Fabric event is registered here and handled by the named method. */
public final class HeistEvents {
    private HeistEvents() {}

    static final Map<UUID, Heist> HEISTS = new HashMap<>();

    static void register() {
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> registerCommands(d));
        ServerTickEvents.END_SERVER_TICK.register(HeistEvents::onServerTick);
        UseBlockCallback.EVENT.register(HeistEvents::onUseBlock);
        AttackBlockCallback.EVENT.register(HeistEvents::onAttackBlock);
        UseEntityCallback.EVENT.register(HeistEvents::onUseEntity);
        AttackEntityCallback.EVENT.register(HeistEvents::onAttackEntity);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(HeistEvents::onAllowDamage);
        ServerLivingEntityEvents.ALLOW_DEATH.register(HeistEvents::onAllowDeath);
        ServerPlayConnectionEvents.DISCONNECT.register(HeistEvents::onDisconnect);
        ServerEntityEvents.ENTITY_LOAD.register(HeistEvents::onEntityLoad);
    }

    static void trace(Sheets.Sys system) {
        GnomeHeist.LOG.debug("system {}", system.id());
    }

    // cmd_heist: /heist, /heist stop (no cheats needed, so it works in any single-player world)
    static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("heist")
            .executes(ctx -> startHeist(ctx.getSource().getPlayerOrException()))
            .then(Commands.literal("stop").executes(ctx -> stopHeist(ctx.getSource().getPlayerOrException()))));
    }

    static int startHeist(ServerPlayer player) {
        if (HEISTS.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal("A heist is already running. /heist stop ends it.").withStyle(ChatFormatting.RED));
            return 0;
        }
        trace(Sheets.SYSTEM_START);
        HEISTS.put(player.getUUID(), Heist.start(player));
        return 1;
    }

    static int stopHeist(ServerPlayer player) {
        Heist h = HEISTS.remove(player.getUUID());
        if (h == null) {
            player.sendSystemMessage(Component.literal("No heist running. /heist starts one.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        trace(Sheets.SYSTEM_END);
        h.end(player, "Called off.");
        return 1;
    }

    // tick: countdown, timer, banking, stun timers, drop-zone particles
    static void onServerTick(MinecraftServer server) {
        if (HEISTS.isEmpty()) return;
        HEISTS.entrySet().removeIf(en -> {
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null) return false; // handled by onDisconnect
            trace(Sheets.SYSTEM_TIMER);
            return !en.getValue().tick(p);
        });
    }

    @Nullable
    static Heist heistOf(Player player, Level world) {
        if (world.isClientSide() || !(player instanceof ServerPlayer)) return null;
        return HEISTS.get(player.getUUID());
    }

    // use_block: right-click a loot block
    static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        Heist h = heistOf(player, world);
        if (h == null) return InteractionResult.PASS;
        trace(Sheets.SYSTEM_GRAB);
        return h.tryGrabBlock((ServerPlayer) player, hit.getBlockPos()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    // attack_block: punching a loot block grabs it on the first hit
    static InteractionResult onAttackBlock(Player player, Level world, InteractionHand hand, BlockPos pos, Direction dir) {
        Heist h = heistOf(player, world);
        if (h == null) return InteractionResult.PASS;
        trace(Sheets.SYSTEM_GRAB);
        return h.tryGrabBlock((ServerPlayer) player, pos) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    // use_entity: right-click the gnome statue
    static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity, @Nullable EntityHitResult hit) {
        Heist h = heistOf(player, world);
        if (h == null) return InteractionResult.PASS;
        trace(Sheets.SYSTEM_GRAB);
        return h.tryGrabStatue((ServerPlayer) player, entity) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    // attack_entity: punch the gnome statue
    static InteractionResult onAttackEntity(Player player, Level world, InteractionHand hand, Entity entity, @Nullable EntityHitResult hit) {
        Heist h = heistOf(player, world);
        if (h == null) return InteractionResult.PASS;
        trace(Sheets.SYSTEM_GRAB);
        return h.tryGrabStatue((ServerPlayer) player, entity) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    // player_damage: a guard landing a hit means caught, never damage
    static boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer player)) return true;
        Heist h = HEISTS.get(player.getUUID());
        if (h == null) return true;
        Entity attacker = source.getEntity();
        if (attacker == null || !h.guards.containsKey(attacker.getUUID())) return true;
        trace(Sheets.SYSTEM_CAUGHT);
        h.caught(player, h.guards.get(attacker.getUUID()));
        return false;
    }

    // guard_death: guards are stunned, never killed
    static boolean onAllowDeath(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof Mob mob) || !mob.getTags().contains(Heist.GUARD_TAG)) return true;
        for (Heist h : HEISTS.values()) {
            if (h.guards.containsKey(mob.getUUID())) {
                trace(Sheets.SYSTEM_STUN);
                h.stun(mob);
                return false;
            }
        }
        return true; // a leftover guard from a heist that no longer exists may die normally
    }

    // disconnect: clean up when the player leaves
    static void onDisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        Heist h = HEISTS.remove(handler.getPlayer().getUUID());
        if (h != null) {
            trace(Sheets.SYSTEM_CLEANUP);
            h.cleanup();
        }
    }

    // entity_load: a world saved mid-heist reloads its guards and statue; nothing owns them any more
    static void onEntityLoad(Entity entity, ServerLevel level) {
        if (!entity.getTags().contains(Heist.GUARD_TAG) && !entity.getTags().contains(Heist.STATUE_TAG)) return;
        for (Heist h : HEISTS.values()) {
            if (h.guards.containsKey(entity.getUUID()) || h.lootStatues.containsKey(entity.getUUID())) return;
        }
        // Not yet registered by a heist being set up this tick? Heist.start adds them right after spawning.
        if (Heist.SPAWNING) return;
        trace(Sheets.SYSTEM_CLEANUP);
        entity.discard();
    }
}
