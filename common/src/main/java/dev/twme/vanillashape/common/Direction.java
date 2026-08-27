package dev.twme.vanillashape.common;

public enum Direction {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

    private final int dx;
    private final int dz;

    Direction(final int dx, final int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() { return dx; }
    public int dz() { return dz; }
    public Direction opposite() { return values()[(ordinal() + 2) & 3]; }
    public Direction left() { return values()[(ordinal() + 3) & 3]; }
    public Direction right() { return values()[(ordinal() + 1) & 3]; }
    public boolean perpendicular(final Direction other) {
        return (ordinal() & 1) != (other.ordinal() & 1);
    }
}
