package gg.gnomeheist;

import gg.gnomeheist.gen.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Builds the house (rooms sheet), the van and drop zone (places sheet) around a heist origin. */
public final class HouseBuilder {
    private HouseBuilder() {}

    // Footprint of everything built, in local coordinates (x east, z south, y=0 is the floor layer).
    static final int MIN_X = -16, MAX_X = 22, MIN_Z = -2, MAX_Z = 16, ROOF_Y = 5, CLEAR_Y = 9;

    static BlockState block(String id) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return b.defaultBlockState();
    }

    static void set(ServerLevel level, BlockPos origin, int x, int y, int z, BlockState state) {
        level.setBlock(origin.offset(x, y, z), state, Block.UPDATE_CLIENTS);
    }

    public static void build(ServerLevel level, BlockPos origin) {
        // Clear the lot and lay ground under it.
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                for (int y = 1; y <= CLEAR_Y; y++) set(level, origin, x, y, z, Blocks.AIR.defaultBlockState());
                set(level, origin, x, 0, z, Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = -3; y < 0; y++) set(level, origin, x, y, z, Blocks.DIRT.defaultBlockState());
            }
        }
        // Rooms: floor, then walls; doorways are cut after every wall exists.
        for (Sheets.Room r : Sheets.ROOMS) {
            BlockState floor = block(r.floor());
            for (int x = r.x1(); x <= r.x2(); x++)
                for (int z = r.z1(); z <= r.z2(); z++) set(level, origin, x, 0, z, floor);
        }
        for (Sheets.Room r : Sheets.ROOMS) {
            BlockState wall = block(r.wall());
            for (int y = 1; y <= 4; y++) {
                for (int x = r.x1(); x <= r.x2(); x++) {
                    set(level, origin, x, y, r.z1(), wall);
                    set(level, origin, x, y, r.z2(), wall);
                }
                for (int z = r.z1(); z <= r.z2(); z++) {
                    set(level, origin, r.x1(), y, z, wall);
                    set(level, origin, r.x2(), y, z, wall);
                }
            }
        }
        // Windows on the outer walls, away from the vault.
        for (int x = 2; x <= 12; x += 3) {
            for (int y = 2; y <= 3; y++) {
                set(level, origin, x, y, 0, Blocks.GLASS_PANE.defaultBlockState());
                set(level, origin, x, y, 14, Blocks.GLASS_PANE.defaultBlockState());
            }
        }
        for (Sheets.Room r : Sheets.ROOMS) {
            for (int[] d : r.doorways()) {
                set(level, origin, d[0], 1, d[1], Blocks.AIR.defaultBlockState());
                set(level, origin, d[0], 2, d[1], Blocks.AIR.defaultBlockState());
            }
        }
        // Roof with lights so the inside is never dark.
        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z <= 14; z++) {
                boolean light = x % 4 == 2 && z % 4 == 2;
                set(level, origin, x, ROOF_Y, z, light ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        // A path from the van to the front door.
        for (int x = -4; x < 0; x++) set(level, origin, x, 0, 7, Blocks.DIRT_PATH.defaultBlockState());
        buildVan(level, origin, Sheets.place("van"));
        Sheets.Place drop = Sheets.place("drop_zone");
        for (int x = drop.x1(); x <= drop.x2(); x++)
            for (int z = drop.z1(); z <= drop.z2(); z++) set(level, origin, x, 0, z, Blocks.YELLOW_CONCRETE.defaultBlockState());
    }

    static void buildVan(ServerLevel level, BlockPos origin, Sheets.Place van) {
        BlockState body = Blocks.WHITE_CONCRETE.defaultBlockState();
        int x1 = van.x1(), x2 = van.x2(), z1 = van.z1() + 1, z2 = van.z2() - 1;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                set(level, origin, x, 1, z, Blocks.GRAY_CONCRETE.defaultBlockState());
                set(level, origin, x, 4, z, body);
                boolean side = z == z1 || z == z2 || x == x1;
                for (int y = 2; y <= 3; y++) if (side) set(level, origin, x, y, z, body);
            }
        }
        // Windscreen at the front (west), side stripe, wheels, open back doors (east).
        for (int z = z1; z <= z2; z++) set(level, origin, x1, 3, z, Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
        for (int x = x1 + 1; x < x2; x++) {
            set(level, origin, x, 3, z1, Blocks.GREEN_CONCRETE.defaultBlockState());
            set(level, origin, x, 3, z2, Blocks.GREEN_CONCRETE.defaultBlockState());
        }
        for (int x : new int[]{x1 + 1, x2 - 1}) {
            set(level, origin, x, 1, van.z1(), Blocks.BLACK_CONCRETE.defaultBlockState());
            set(level, origin, x, 1, van.z2(), Blocks.BLACK_CONCRETE.defaultBlockState());
        }
    }
}
