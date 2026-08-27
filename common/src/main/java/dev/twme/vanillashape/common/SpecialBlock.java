package dev.twme.vanillashape.common;

import java.util.Objects;

/** Complete authoritative record for one rendered block coordinate. */
public record SpecialBlock(
        String world,
        int x,
        int y,
        int z,
        ShapeType shape,
        String material,
        String model,
        Direction facing,
        CornerShape corner,
        int flags) {

    public static final int WATERLOGGED = 1;
    public static final int TOP = 1 << 1;
    public static final int OPEN = 1 << 2;
    public static final int HINGE_RIGHT = 1 << 3;
    public static final int POWERED = 1 << 4;
    public static final int NORTH = 1 << 5;
    public static final int EAST = 1 << 6;
    public static final int SOUTH = 1 << 7;
    public static final int WEST = 1 << 8;
    public static final int DOOR_UPPER = 1 << 9;
    public static final int WALL_UP = 1 << 10;
    public static final int WALL_TALL_NORTH = 1 << 11;
    public static final int WALL_TALL_EAST = 1 << 12;
    public static final int WALL_TALL_SOUTH = 1 << 13;
    public static final int WALL_TALL_WEST = 1 << 14;
    public static final int ALL_FLAGS = WATERLOGGED | TOP | OPEN | HINGE_RIGHT | POWERED
            | NORTH | EAST | SOUTH | WEST | DOOR_UPPER | WALL_UP
            | WALL_TALL_NORTH | WALL_TALL_EAST | WALL_TALL_SOUTH | WALL_TALL_WEST;

    public SpecialBlock {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(corner, "corner");
    }

    /** Backwards-compatible constructor for the original fixed shapes. */
    public SpecialBlock(final String world, final int x, final int y, final int z,
                        final ShapeType shape, final String material, final Direction facing,
                        final CornerShape corner, final int flags) {
        this(world, x, y, z, shape, material, "", facing, corner, flags);
    }

    public SpecialBlock withCorner(final CornerShape value) {
        return new SpecialBlock(world, x, y, z, shape, material, model, facing, value, flags);
    }

    public SpecialBlock withFlags(final int value) {
        return new SpecialBlock(world, x, y, z, shape, material, model, facing, corner, value);
    }

    public SpecialBlock withFacing(final Direction value) {
        return new SpecialBlock(world, x, y, z, shape, material, model, value, corner, flags);
    }

    public SpecialBlock withMaterial(final String value) {
        return new SpecialBlock(world, x, y, z, shape, value, model, facing, corner, flags);
    }

    public SpecialBlock withModel(final String value) {
        return new SpecialBlock(world, x, y, z, shape, material, value, facing, corner, flags);
    }

    public SpecialBlock withShape(final ShapeType value) {
        return new SpecialBlock(world, x, y, z, value, material, model, facing, corner, flags);
    }

    public SpecialBlock at(final String valueWorld, final int valueX, final int valueY, final int valueZ) {
        return new SpecialBlock(valueWorld, valueX, valueY, valueZ,
                shape, material, model, facing, corner, flags);
    }
}
