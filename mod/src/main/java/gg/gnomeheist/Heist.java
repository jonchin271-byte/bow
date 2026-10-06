package gg.gnomeheist;

import gg.gnomeheist.gen.Sheets;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One player's heist: the systems sheet's start, timer, grab, bank, caught, stun and end rows. */
public final class Heist {
    public static final String GUARD_TAG = "gnomeheist_guard";
    public static final String STATUE_TAG = "gnomeheist_statue";
    /** True while start() spawns guards and the statue, before they are registered. */
    static boolean SPAWNING;

    final UUID playerId;
    final ServerLevel level;
    final BlockPos origin;
    int countdownTicks;
    int ticksLeft;
    int graceTicks;
    int age;
    final Map<BlockPos, Sheets.Loot> lootBlocks = new HashMap<>();
    final Map<UUID, Sheets.Loot> lootStatues = new HashMap<>();
    final Map<UUID, Sheets.Guard> guards = new LinkedHashMap<>();
    final Map<UUID, Integer> stunned = new HashMap<>();
    final List<Sheets.Loot> carried = new ArrayList<>();
    final List<Sheets.Loot> banked = new ArrayList<>();
    final ServerBossEvent bar = new ServerBossEvent(Component.literal("Heist"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);

    Heist(ServerPlayer player, BlockPos origin) {
        this.playerId = player.getUUID();
        this.level = player.serverLevel();
        this.origin = origin;
    }

    // --- start -----------------------------------------------------------------------------
    static Heist start(ServerPlayer player) {
        Sheets.Place start = Sheets.place("start");
        BlockPos feet = player.blockPosition();
        // The player is standing on the start point: origin is where local (0,0,0) lands.
        BlockPos origin = feet.offset(-start.x1(), -1 - start.y1(), -start.z1());
        Heist h = new Heist(player, origin);
        HouseBuilder.build(h.level, origin);
        SPAWNING = true;
        try {
            for (Sheets.Loot l : Sheets.LOOT) h.placeLoot(l);
            for (Sheets.Guard g : Sheets.GUARDS) h.spawnGuard(g);
        } finally {
            SPAWNING = false;
        }
        h.countdownTicks = Sheets.intParam(Sheets.SYSTEM_START, "countdownSeconds") * 20;
        h.ticksLeft = Sheets.intParam(Sheets.SYSTEM_TIMER, "seconds") * 20;
        h.bar.addPlayer(player);
        h.toStart(player);
        GnomeHeist.LOG.info("Heist started at {} ({} loot, {} guards)", origin, Sheets.LOOT.size(), h.guards.size());
        return h;
    }

    BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    void placeLoot(Sheets.Loot l) {
        BlockPos pos = at(l.x(), l.y(), l.z());
        if (l.kind().equals("statue")) {
            // A small armour stand dressed as a garden gnome: red pointed hat, blue coat, gold boots.
            ArmorStand gnome = new ArmorStand(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            CompoundTag shape = new CompoundTag();
            shape.putBoolean("Small", true);
            shape.putBoolean("NoBasePlate", true);
            gnome.readAdditionalSaveData(shape);
            gnome.setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, 0xCC3333));
            gnome.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0x3333FF));
            gnome.setItemSlot(EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0x3333FF));
            gnome.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.GOLDEN_BOOTS));
            gnome.setYRot(90f);
            gnome.setInvulnerable(true);
            gnome.setCustomName(Component.literal(l.name()).withStyle(ChatFormatting.GOLD));
            gnome.setCustomNameVisible(true);
            gnome.addTag(STATUE_TAG);
            level.addFreshEntity(gnome);
            lootStatues.put(gnome.getUUID(), l);
            // The statue's block column is its plinth: the gnome stands on it at eye level.
            set(pos, HouseBuilder.block(l.block()));
        } else {
            set(pos, HouseBuilder.block(l.block()));
            lootBlocks.put(pos, l);
        }
    }

    static ItemStack dyed(net.minecraft.world.item.Item item, int rgb) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, false));
        return stack;
    }

    private void set(BlockPos pos, net.minecraft.world.level.block.state.BlockState s) {
        level.setBlockAndUpdate(pos, s);
    }

    void spawnGuard(Sheets.Guard g) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(g.entity()));
        Entity e = type.create(level);
        if (!(e instanceof Mob mob)) {
            GnomeHeist.LOG.error("Guard {} is not a mob: {}", g.id(), g.entity());
            return;
        }
        BlockPos pos = at(g.x(), g.y(), g.z());
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.COMMAND, null);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(g.health());
        mob.setHealth((float) g.health());
        mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(g.speed());
        mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(g.followRange());
        for (EquipmentSlot slot : EquipmentSlot.values()) mob.setItemSlot(slot, ItemStack.EMPTY);
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(g.helmet()))));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(g.mainhand()))));
        for (EquipmentSlot slot : EquipmentSlot.values()) mob.setDropChance(slot, 0f);
        mob.setCustomName(guardName(g, false));
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
        mob.setNoAi(true); // frozen until the countdown ends
        mob.addTag(GUARD_TAG);
        level.addFreshEntity(mob);
        guards.put(mob.getUUID(), g);
    }

    static Component guardName(Sheets.Guard g, boolean stunned) {
        return stunned
            ? Component.literal(g.name() + " (stunned)").withStyle(ChatFormatting.GRAY)
            : Component.literal(g.name()).withStyle(ChatFormatting.RED);
    }

    void toStart(ServerPlayer player) {
        Sheets.Place s = Sheets.place("start");
        BlockPos p = at(s.x1(), s.y1() + 1, s.z1());
        // Face the front door (east).
        player.teleportTo(level, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, -90f, 0f);
    }

    boolean running() {
        return countdownTicks <= 0 && ticksLeft > 0;
    }

    // --- tick: countdown, timer, bank, stun, HUD ----------------------------------------------
    /** Returns false when the heist is over and should be forgotten. */
    boolean tick(ServerPlayer player) {
        age++;
        if (countdownTicks > 0) {
            if (countdownTicks % 20 == 0) {
                title(player, Component.literal(String.valueOf(countdownTicks / 20)).withStyle(ChatFormatting.YELLOW),
                    Component.literal("Steal loot, bank it at the van. Don't get caught."), 0, 22, 0);
                sound(player, SoundEvents.NOTE_BLOCK_HAT.value(), 1f);
            }
            countdownTicks--;
            if (countdownTicks == 0) {
                title(player, Component.literal("GO!").withStyle(ChatFormatting.GREEN), Component.empty(), 0, 20, 10);
                for (UUID id : guards.keySet()) {
                    if (level.getEntity(id) instanceof Mob mob) {
                        mob.setNoAi(false);
                        mob.setTarget(player);
                    }
                }
            }
            updateBar();
            return true;
        }
        if (graceTicks > 0 && --graceTicks == 0) {
            // caught: the head start is over, guards leave their posts again
            for (UUID id : guards.keySet()) {
                if (!stunned.containsKey(id) && level.getEntity(id) instanceof Mob mob) {
                    mob.setNoAi(false);
                    mob.setTarget(player);
                }
            }
        }
        ticksLeft--;

        // stun: count down frozen guards
        stunned.replaceAll((id, t) -> t - 1);
        stunned.entrySet().removeIf(en -> {
            if (en.getValue() > 0) return false;
            if (level.getEntity(en.getKey()) instanceof Mob mob) {
                mob.setCustomName(guardName(guards.get(en.getKey()), false));
                if (graceTicks == 0) {
                    mob.setNoAi(false);
                    mob.setTarget(player);
                }
            }
            return true;
        });

        // bank: carried loot counts once the player stands in the drop zone
        Sheets.Place zone = Sheets.place((String) Sheets.SYSTEM_BANK.params().get("zone"));
        AABB zoneBox = new AABB(Vec(at(zone.x1(), zone.y1(), zone.z1())), Vec(at(zone.x2() + 1, zone.y2() + 1, zone.z2() + 1)));
        if (!carried.isEmpty() && zoneBox.intersects(player.getBoundingBox())) bank(player);
        if (age % 10 == 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, zoneBox.getCenter().x, zoneBox.minY + 1.2, zoneBox.getCenter().z, 6, 1.0, 0.4, 2.0, 0);
            actionBar(player);
        }
        int warn = Sheets.intParam(Sheets.SYSTEM_TIMER, "warnAtSeconds") * 20;
        if (ticksLeft <= warn && ticksLeft % 20 == 0 && ticksLeft > 0) sound(player, SoundEvents.NOTE_BLOCK_HAT.value(), 1.6f);
        updateBar();
        if (ticksLeft <= 0) {
            end(player, "Time's up!");
            return false;
        }
        return true;
    }

    private static net.minecraft.world.phys.Vec3 Vec(BlockPos p) {
        return new net.minecraft.world.phys.Vec3(p.getX(), p.getY(), p.getZ());
    }

    void updateBar() {
        int secs = Math.max(0, (countdownTicks > 0 ? Sheets.intParam(Sheets.SYSTEM_TIMER, "seconds") * 20 : ticksLeft) + 19) / 20;
        bar.setName(Component.literal(String.format("Heist  %d:%02d   |   Banked %d", secs / 60, secs % 60, total(banked))));
        bar.setProgress(Math.max(0f, Math.min(1f, ticksLeft / (float) (Sheets.intParam(Sheets.SYSTEM_TIMER, "seconds") * 20))));
        bar.setColor(ticksLeft <= Sheets.intParam(Sheets.SYSTEM_TIMER, "warnAtSeconds") * 20 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
    }

    void actionBar(ServerPlayer player) {
        int max = Sheets.intParam(Sheets.SYSTEM_GRAB, "maxCarried");
        String text = carried.isEmpty()
            ? "Hands empty: grab loot (right-click or punch it)"
            : "Carrying " + String.join(", ", carried.stream().map(Sheets.Loot::name).toList())
                + " (" + total(carried) + ")  " + (carried.size() >= max ? "HANDS FULL: get to the van!" : "→ bank at the van");
        player.displayClientMessage(Component.literal(text).withStyle(carried.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.GOLD), true);
    }

    // --- grab ----------------------------------------------------------------------------------
    boolean tryGrabBlock(ServerPlayer player, BlockPos pos) {
        Sheets.Loot l = lootBlocks.get(pos);
        if (l == null) return false;
        if (!running()) return true; // swallow the click during the countdown
        if (!roomInHands(player)) return true;
        lootBlocks.remove(pos);
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        grabbed(player, l);
        return true;
    }

    boolean tryGrabStatue(ServerPlayer player, Entity e) {
        Sheets.Loot l = lootStatues.get(e.getUUID());
        if (l == null) return false;
        if (!running()) return true;
        if (!roomInHands(player)) return true;
        lootStatues.remove(e.getUUID());
        e.discard();
        grabbed(player, l);
        return true;
    }

    private boolean roomInHands(ServerPlayer player) {
        if (carried.size() < Sheets.intParam(Sheets.SYSTEM_GRAB, "maxCarried")) return true;
        sound(player, SoundEvents.VILLAGER_NO, 1f);
        actionBar(player);
        return false;
    }

    private void grabbed(ServerPlayer player, Sheets.Loot l) {
        carried.add(l);
        sound(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f);
        player.sendSystemMessage(Component.literal("Grabbed " + l.name() + " (" + l.points() + ")").withStyle(ChatFormatting.GOLD));
        actionBar(player);
        GnomeHeist.LOG.info("Heist grab {} ({}), carrying {}", l.id(), l.points(), carried.size());
    }

    // --- bank ----------------------------------------------------------------------------------
    void bank(ServerPlayer player) {
        int value = total(carried);
        banked.addAll(carried);
        carried.clear();
        sound(player, SoundEvents.PLAYER_LEVELUP, 1.2f);
        title(player, Component.empty(), Component.literal("Banked +" + value).withStyle(ChatFormatting.GREEN), 0, 30, 10);
        GnomeHeist.LOG.info("Heist bank +{} (total {})", value, total(banked));
    }

    // --- caught --------------------------------------------------------------------------------
    void caught(ServerPlayer player, Sheets.Guard by) {
        if (graceTicks > 0 || !running()) return;
        int lost = total(carried);
        carried.clear();
        graceTicks = Sheets.intParam(Sheets.SYSTEM_CAUGHT, "graceTicks");
        toStart(player);
        for (Map.Entry<UUID, Sheets.Guard> en : guards.entrySet()) {
            if (level.getEntity(en.getKey()) instanceof Mob mob) {
                BlockPos post = at(en.getValue().x(), en.getValue().y(), en.getValue().z());
                mob.teleportTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
                mob.setTarget(null);
                mob.setNoAi(true);
            }
        }
        title(player, Component.literal("CAUGHT!").withStyle(ChatFormatting.RED),
            Component.literal(by.name() + " threw you out"), 0, 30, 10);
        sound(player, SoundEvents.VILLAGER_NO, 0.7f);
        if (lost > 0) player.sendSystemMessage(Component.literal("You lost " + lost + " worth of loot.").withStyle(ChatFormatting.RED));
        GnomeHeist.LOG.info("Heist caught by {}, lost {}", by.id(), lost);
    }

    // --- stun ----------------------------------------------------------------------------------
    void stun(Mob mob) {
        Sheets.Guard g = guards.get(mob.getUUID());
        mob.setHealth(mob.getMaxHealth());
        mob.setNoAi(true);
        mob.setTarget(null);
        mob.setCustomName(guardName(g, true));
        stunned.put(mob.getUUID(), g.stunSeconds() * 20);
        GnomeHeist.LOG.info("Heist stun {} for {}s", g.id(), g.stunSeconds());
    }

    // --- end -----------------------------------------------------------------------------------
    void end(ServerPlayer player, String why) {
        int lost = total(carried);
        carried.clear();
        int score = total(banked);
        int best = Sheets.LOOT.stream().mapToInt(Sheets.Loot::points).sum();
        title(player, Component.literal("Heist over: " + score).withStyle(ChatFormatting.GOLD),
            Component.literal(why + "  Best possible: " + best), 10, 100, 20);
        sound(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        player.sendSystemMessage(Component.literal("=== Heist score card ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Sheets.Loot l : banked) player.sendSystemMessage(Component.literal("  " + l.name() + "  +" + l.points()).withStyle(ChatFormatting.GREEN));
        if (banked.isEmpty()) player.sendSystemMessage(Component.literal("  Nothing banked.").withStyle(ChatFormatting.GRAY));
        if (lost > 0) player.sendSystemMessage(Component.literal("  Dropped at the end: " + lost).withStyle(ChatFormatting.RED));
        player.sendSystemMessage(Component.literal("  Total " + score + " / " + best + ".  /heist to go again.").withStyle(ChatFormatting.YELLOW));
        cleanup();
        GnomeHeist.LOG.info("Heist end ({}) score {} / {}", why, score, best);
    }

    void cleanup() {
        bar.removeAllPlayers();
        for (UUID id : guards.keySet()) {
            Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
        for (UUID id : lootStatues.keySet()) {
            Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
        for (BlockPos pos : lootBlocks.keySet()) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        guards.clear();
        lootStatues.clear();
        lootBlocks.clear();
    }

    // --- helpers -------------------------------------------------------------------------------
    static int total(List<Sheets.Loot> list) {
        return list.stream().mapToInt(Sheets.Loot::points).sum();
    }

    void sound(ServerPlayer player, SoundEvent s, float pitch) {
        level.playSound(null, player.blockPosition(), s, SoundSource.PLAYERS, 1f, pitch);
    }

    static void title(ServerPlayer p, Component title, Component sub, int in, int stay, int out) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
        p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }
}
