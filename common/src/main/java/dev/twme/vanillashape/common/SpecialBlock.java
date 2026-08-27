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
    public static final int ALL_FLAGS = WATERLOGGED | TOP | OPEN | HINGE_RIGHT | POWERED
            | NORTH | EAST | SOUTH | WEST | DOOR_UPPER;

    public SpecialBlock {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(corner, "corner");
    }

    public SpecialBlock withCorner(final CornerShape value) {
        return new SpecialBlock(world, x, y, z, shape, material, facing, value, flags);
    }

    public SpecialBlock withFlags(final int value) {
        return new SpecialBlock(world, x, y, z, shape, material, facing, corner, value);
    }

    public SpecialBlock withFacing(final Direction value) {
        return new SpecialBlock(world, x, y, z, shape, material, value, corner, flags);
    }

    public SpecialBlock withMaterial(final String value) {
        return new SpecialBlock(world, x, y, z, shape, value, facing, corner, flags);
    }

    public SpecialBlock withShape(final ShapeType value) {
        return new SpecialBlock(world, x, y, z, value, material, facing, corner, flags);
    }

    public SpecialBlock at(final String valueWorld, final int valueX, final int valueY, final int valueZ) {
        return new SpecialBlock(valueWorld, valueX, valueY, valueZ,
                shape, material, facing, corner, flags);
    }
}
