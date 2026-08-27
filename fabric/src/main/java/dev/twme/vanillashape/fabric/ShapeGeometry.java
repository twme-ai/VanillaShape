package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.SpecialBlock;

import java.util.ArrayList;
import java.util.List;

final class ShapeGeometry {
    record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
    record Surface(net.minecraft.core.Direction direction,
                   float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
    @FunctionalInterface interface NeighborLookup { SpecialBlock get(int dx, int dy, int dz); }

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
            case MODEL -> List.of(new Box(0, 0, 0, 1, 1, 1));
        };
        if (block.shape() == dev.twme.vanillashape.common.ShapeType.WALL
                || block.shape() == dev.twme.vanillashape.common.ShapeType.FENCE) return canonical;
        return rotate(canonical, block.facing());
    }

    /** Builds only the boundary of the union, removing internal/coplanar faces between component boxes. */
    static List<Surface> surfaces(final SpecialBlock block, final NeighborLookup neighbors) {
        final List<Box> boxes = boxes(block);
        final java.util.TreeSet<Float> xs = coordinates(boxes, true, false, false);
        final java.util.TreeSet<Float> ys = coordinates(boxes, false, true, false);
        final java.util.TreeSet<Float> zs = coordinates(boxes, false, false, true);
        addProjectedCoordinates(xs, ys, zs, neighbors);
        final Float[] x = xs.toArray(Float[]::new), y = ys.toArray(Float[]::new), z = zs.toArray(Float[]::new);
        final List<Surface> result = new ArrayList<>();
        for (int ix = 0; ix + 1 < x.length; ix++) for (int iy = 0; iy + 1 < y.length; iy++) {
            for (int iz = 0; iz + 1 < z.length; iz++) {
                final float cx = midpoint(x[ix], x[ix + 1]);
                final float cy = midpoint(y[iy], y[iy + 1]);
                final float cz = midpoint(z[iz], z[iz + 1]);
                if (!occupied(boxes, cx, cy, cz)) continue;
                addIfExposed(result, boxes, neighbors, net.minecraft.core.Direction.WEST,
                        x[ix], y[iy], z[iz], x[ix], y[iy + 1], z[iz + 1], cx, cy, cz);
                addIfExposed(result, boxes, neighbors, net.minecraft.core.Direction.EAST,
                        x[ix + 1], y[iy], z[iz], x[ix + 1], y[iy + 1], z[iz + 1], cx, cy, cz);
                addIfExposed(result, boxes, neighbors, net.minecraft.core.Direction.DOWN,
                        x[ix], y[iy], z[iz], x[ix + 1], y[iy], z[iz + 1], cx, cy, cz);
                addIfExposed(result, boxes, neighbors, net.minecraft.core.Direction.UP,
                        x[ix], y[iy + 1], z[iz], x[ix + 1], y[iy + 1], z[iz + 1], cx, cy, cz);
                addIfExposed(result, boxes, neighbors, net.minecraft.core.Direction.NORTH,
                        x[ix], y[iy], z[iz], x[ix + 1], y[iy + 1], z[iz], cx, cy, cz);
                addIfExposed(result, boxes, neighbors, net.minecraft.core.Direction.SOUTH,
                        x[ix], y[iy], z[iz + 1], x[ix + 1], y[iy + 1], z[iz + 1], cx, cy, cz);
            }
        }
        return result;
    }

    static boolean coversFace(final SpecialBlock block, final net.minecraft.core.Direction face) {
        if (block.shape() == dev.twme.vanillashape.common.ShapeType.MODEL) return false;
        final List<Box> touching = boxes(block).stream().filter(box -> switch (face) {
            case WEST -> box.minX <= 0; case EAST -> box.maxX >= 1;
            case DOWN -> box.minY <= 0; case UP -> box.maxY >= 1;
            case NORTH -> box.minZ <= 0; case SOUTH -> box.maxZ >= 1;
        }).toList();
        if (touching.isEmpty()) return false;
        final java.util.TreeSet<Float> first = new java.util.TreeSet<>(java.util.Set.of(0f, 1f));
        final java.util.TreeSet<Float> second = new java.util.TreeSet<>(java.util.Set.of(0f, 1f));
        for (final Box box : touching) switch (face.getAxis()) {
            case X -> { first.add(box.minY); first.add(box.maxY); second.add(box.minZ); second.add(box.maxZ); }
            case Y -> { first.add(box.minX); first.add(box.maxX); second.add(box.minZ); second.add(box.maxZ); }
            case Z -> { first.add(box.minX); first.add(box.maxX); second.add(box.minY); second.add(box.maxY); }
        };
        final Float[] a = first.toArray(Float[]::new), b = second.toArray(Float[]::new);
        for (int ia = 0; ia + 1 < a.length; ia++) for (int ib = 0; ib + 1 < b.length; ib++) {
            final float ca = midpoint(a[ia], a[ia + 1]), cb = midpoint(b[ib], b[ib + 1]);
            boolean covered = false;
            for (final Box box : touching) {
                covered = switch (face.getAxis()) {
                    case X -> ca > box.minY && ca < box.maxY && cb > box.minZ && cb < box.maxZ;
                    case Y -> ca > box.minX && ca < box.maxX && cb > box.minZ && cb < box.maxZ;
                    case Z -> ca > box.minX && ca < box.maxX && cb > box.minY && cb < box.maxY;
                };
                if (covered) break;
            }
            if (!covered) return false;
        }
        return true;
    }

    private static void addIfExposed(final List<Surface> result, final List<Box> boxes,
                                     final NeighborLookup neighbors, final net.minecraft.core.Direction direction,
                                     final float minX, final float minY, final float minZ,
                                     final float maxX, final float maxY, final float maxZ,
                                     final float cx, final float cy, final float cz) {
        final float epsilon = 1.0e-4f;
        final float px = direction == net.minecraft.core.Direction.WEST ? minX - epsilon
                : direction == net.minecraft.core.Direction.EAST ? maxX + epsilon : cx;
        final float py = direction == net.minecraft.core.Direction.DOWN ? minY - epsilon
                : direction == net.minecraft.core.Direction.UP ? maxY + epsilon : cy;
        final float pz = direction == net.minecraft.core.Direction.NORTH ? minZ - epsilon
                : direction == net.minecraft.core.Direction.SOUTH ? maxZ + epsilon : cz;
        if (occupied(boxes, px, py, pz)) return;
        if (outsideOccupied(neighbors, px, py, pz)) return;
        result.add(new Surface(direction, minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static boolean outsideOccupied(final NeighborLookup neighbors,
                                           final float x, final float y, final float z) {
        int dx = 0, dy = 0, dz = 0;
        float lx = x, ly = y, lz = z;
        if (x < 0) { dx = -1; lx += 1; } else if (x > 1) { dx = 1; lx -= 1; }
        if (y < 0) { dy = -1; ly += 1; } else if (y > 1) { dy = 1; ly -= 1; }
        if (z < 0) { dz = -1; lz += 1; } else if (z > 1) { dz = 1; lz -= 1; }
        if (dx == 0 && dy == 0 && dz == 0) return false;
        final SpecialBlock neighbor = neighbors.get(dx, dy, dz);
        return neighbor != null && neighbor.shape() != dev.twme.vanillashape.common.ShapeType.MODEL
                && occupied(boxes(neighbor), lx, ly, lz);
    }

    private static void addProjectedCoordinates(final java.util.Set<Float> xs,
                                                final java.util.Set<Float> ys,
                                                final java.util.Set<Float> zs,
                                                final NeighborLookup neighbors) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
            final SpecialBlock neighbor = neighbors.get(dx, dy, dz);
            if (neighbor == null || neighbor.shape() == dev.twme.vanillashape.common.ShapeType.MODEL) continue;
            for (final Box box : boxes(neighbor)) {
                if (dx != 0) { ys.add(box.minY); ys.add(box.maxY); zs.add(box.minZ); zs.add(box.maxZ); }
                if (dy != 0) { xs.add(box.minX); xs.add(box.maxX); zs.add(box.minZ); zs.add(box.maxZ); }
                if (dz != 0) { xs.add(box.minX); xs.add(box.maxX); ys.add(box.minY); ys.add(box.maxY); }
            }
        }
    }

    private static java.util.TreeSet<Float> coordinates(final List<Box> boxes,
                                                        final boolean x, final boolean y, final boolean z) {
        final java.util.TreeSet<Float> result = new java.util.TreeSet<>();
        result.add(0f); result.add(1f);
        for (final Box box : boxes) {
            if (x) { result.add(box.minX); result.add(box.maxX); }
            if (y) { result.add(box.minY); result.add(box.maxY); }
            if (z) { result.add(box.minZ); result.add(box.maxZ); }
        }
        return result;
    }

    private static boolean occupied(final List<Box> boxes, final float x, final float y, final float z) {
        for (final Box box : boxes) if (x > box.minX && x < box.maxX
                && y > box.minY && y < box.maxY && z > box.minZ && z < box.maxZ) return true;
        return false;
    }

    private static float midpoint(final float a, final float b) { return (a + b) / 2; }

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
        if (has(flags, SpecialBlock.WALL_UP)) result.add(new Box(.25f, 0, .25f, .75f, 1, .75f));
        if (has(flags, SpecialBlock.NORTH)) result.add(new Box(.3125f, 0, 0, .6875f,
                has(flags, SpecialBlock.WALL_TALL_NORTH) ? 1 : .875f, .5f));
        if (has(flags, SpecialBlock.SOUTH)) result.add(new Box(.3125f, 0, .5f, .6875f,
                has(flags, SpecialBlock.WALL_TALL_SOUTH) ? 1 : .875f, 1));
        if (has(flags, SpecialBlock.WEST)) result.add(new Box(0, 0, .3125f, .5f,
                has(flags, SpecialBlock.WALL_TALL_WEST) ? 1 : .875f, .6875f));
        if (has(flags, SpecialBlock.EAST)) result.add(new Box(.5f, 0, .3125f, 1,
                has(flags, SpecialBlock.WALL_TALL_EAST) ? 1 : .875f, .6875f));
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
        final float p2 = 2 / 16f, p5 = 5 / 16f, p6 = 6 / 16f, p7 = 7 / 16f;
        final float p8 = 8 / 16f, p9 = 9 / 16f, p10 = 10 / 16f, p12 = 12 / 16f;
        final float p13 = 13 / 16f, p14 = 14 / 16f, p15 = 15 / 16f;
        if (has(block.flags(), SpecialBlock.OPEN)) {
            return List.of(
                    new Box(0, p5, p7, p2, 1, p9),
                    new Box(p14, p5, p7, 1, 1, p9),
                    new Box(0, p6, p13, p2, p15, p15),
                    new Box(p14, p6, p13, 1, p15, p15),
                    new Box(0, p6, p9, p2, p9, p13),
                    new Box(0, p12, p9, p2, p15, p13),
                    new Box(p14, p6, p9, 1, p9, p13),
                    new Box(p14, p12, p9, 1, p15, p13));
        }
        return List.of(
                new Box(0, p5, p7, p2, 1, p9),
                new Box(p14, p5, p7, 1, 1, p9),
                new Box(p6, p6, p7, p8, p15, p9),
                new Box(p8, p6, p7, p10, p15, p9),
                new Box(p2, p6, p7, p6, p9, p9),
                new Box(p2, p12, p7, p6, p15, p9),
                new Box(p10, p6, p7, p14, p9, p9),
                new Box(p10, p12, p7, p14, p15, p9));
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
