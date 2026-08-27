package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.SpecialBlock;

import java.util.ArrayList;
import java.util.List;

final class ShapeGeometry {
    record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}

    private ShapeGeometry() {}

    static List<Box> boxes(final SpecialBlock block) {
        final List<Box> canonical = switch (block.shape()) {
            case SLAB -> List.of(new Box(0, top(block) ? .5f : 0, 0, 1, top(block) ? 1 : .5f, 1));
            case STAIRS -> stairs(block);
            case VERTICAL_SLAB -> verticalSlab(block.corner());
            case WALL -> wall(block.flags());
            case FENCE -> fence(block.flags());
            case FENCE_GATE -> fenceGate(block);
            case DOOR -> door(block);
            case TRAPDOOR -> trapdoor(block);
        };
        if (block.shape() == dev.twme.vanillashape.common.ShapeType.WALL
                || block.shape() == dev.twme.vanillashape.common.ShapeType.FENCE) return canonical;
        return rotate(canonical, block.facing());
    }

    private static List<Box> stairs(final SpecialBlock block) {
        final boolean top = top(block);
        final float baseMin = top ? .5f : 0, baseMax = top ? 1 : .5f;
        final float stepMin = top ? 0 : .5f, stepMax = top ? .5f : 1;
        final List<Box> result = new ArrayList<>();
        result.add(new Box(0, baseMin, 0, 1, baseMax, 1));
        for (final Box footprint : footprint(block.corner())) {
            result.add(new Box(footprint.minX, stepMin, footprint.minZ, footprint.maxX, stepMax, footprint.maxZ));
        }
        return result;
    }

    private static List<Box> verticalSlab(final CornerShape corner) {
        final List<Box> result = new ArrayList<>();
        for (final Box footprint : footprint(corner)) {
            result.add(new Box(footprint.minX, 0, footprint.minZ, footprint.maxX, 1, footprint.maxZ));
        }
        return result;
    }

    private static List<Box> footprint(final CornerShape corner) {
        return switch (corner) {
            case STRAIGHT -> List.of(new Box(0, 0, 0, 1, 0, .5f));
            case OUTER_LEFT -> List.of(new Box(0, 0, 0, .5f, 0, .5f));
            case OUTER_RIGHT -> List.of(new Box(.5f, 0, 0, 1, 0, .5f));
            case INNER_LEFT -> List.of(
                    new Box(0, 0, 0, 1, 0, .5f), new Box(0, 0, .5f, .5f, 0, 1));
            case INNER_RIGHT -> List.of(
                    new Box(0, 0, 0, 1, 0, .5f), new Box(.5f, 0, .5f, 1, 0, 1));
        };
    }

    private static List<Box> wall(final int flags) {
        final List<Box> result = new ArrayList<>();
        result.add(new Box(.25f, 0, .25f, .75f, 1, .75f));
        if (has(flags, SpecialBlock.NORTH)) result.add(new Box(.3125f, 0, 0, .6875f, .8125f, .5f));
        if (has(flags, SpecialBlock.SOUTH)) result.add(new Box(.3125f, 0, .5f, .6875f, .8125f, 1));
        if (has(flags, SpecialBlock.WEST)) result.add(new Box(0, 0, .3125f, .5f, .8125f, .6875f));
        if (has(flags, SpecialBlock.EAST)) result.add(new Box(.5f, 0, .3125f, 1, .8125f, .6875f));
        return result;
    }

    private static List<Box> fence(final int flags) {
        final List<Box> result = new ArrayList<>();
        result.add(new Box(.375f, 0, .375f, .625f, 1, .625f));
        fenceArm(result, flags, SpecialBlock.NORTH, .4375f, 0, .5625f, .5f);
        fenceArm(result, flags, SpecialBlock.SOUTH, .4375f, .5f, .5625f, 1);
        if (has(flags, SpecialBlock.WEST)) addRails(result, 0, .4375f, .5f, .5625f);
        if (has(flags, SpecialBlock.EAST)) addRails(result, .5f, .4375f, 1, .5625f);
        return result;
    }

    private static void fenceArm(final List<Box> out, final int flags, final int bit,
                                 final float minX, final float minZ, final float maxX, final float maxZ) {
        if (!has(flags, bit)) return;
        out.add(new Box(minX, .375f, minZ, maxX, .5625f, maxZ));
        out.add(new Box(minX, .75f, minZ, maxX, .9375f, maxZ));
    }

    private static void addRails(final List<Box> out, final float minX, final float minZ,
                                 final float maxX, final float maxZ) {
        out.add(new Box(minX, .375f, minZ, maxX, .5625f, maxZ));
        out.add(new Box(minX, .75f, minZ, maxX, .9375f, maxZ));
    }

    private static List<Box> fenceGate(final SpecialBlock block) {
        if (has(block.flags(), SpecialBlock.OPEN)) {
            return List.of(new Box(0, .3125f, 0, .1875f, .9375f, .5f),
                    new Box(.8125f, .3125f, 0, 1, .9375f, .5f));
        }
        return List.of(new Box(0, .375f, .4375f, 1, .5625f, .5625f),
                new Box(0, .75f, .4375f, 1, .9375f, .5625f));
    }

    private static List<Box> door(final SpecialBlock block) {
        if (!has(block.flags(), SpecialBlock.OPEN)) return List.of(new Box(0, 0, 0, 1, 1, .1875f));
        return has(block.flags(), SpecialBlock.HINGE_RIGHT)
                ? List.of(new Box(.8125f, 0, 0, 1, 1, 1))
                : List.of(new Box(0, 0, 0, .1875f, 1, 1));
    }

    private static List<Box> trapdoor(final SpecialBlock block) {
        if (has(block.flags(), SpecialBlock.OPEN)) return List.of(new Box(0, 0, 0, 1, 1, .1875f));
        return top(block) ? List.of(new Box(0, .8125f, 0, 1, 1, 1))
                : List.of(new Box(0, 0, 0, 1, .1875f, 1));
    }

    private static List<Box> rotate(final List<Box> boxes, final Direction facing) {
        final int turns = switch (facing) { case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; case WEST -> 3; };
        if (turns == 0) return boxes;
        final List<Box> result = new ArrayList<>(boxes.size());
        for (final Box box : boxes) {
            float minX = box.minX, minZ = box.minZ, maxX = box.maxX, maxZ = box.maxZ;
            for (int turn = 0; turn < turns; turn++) {
                final float nextMinX = 1 - maxZ, nextMaxX = 1 - minZ;
                final float nextMinZ = minX, nextMaxZ = maxX;
                minX = nextMinX; maxX = nextMaxX; minZ = nextMinZ; maxZ = nextMaxZ;
            }
            result.add(new Box(minX, box.minY, minZ, maxX, box.maxY, maxZ));
        }
        return result;
    }

    private static boolean top(final SpecialBlock block) { return has(block.flags(), SpecialBlock.TOP); }
    private static boolean has(final int flags, final int bit) { return (flags & bit) != 0; }
}
