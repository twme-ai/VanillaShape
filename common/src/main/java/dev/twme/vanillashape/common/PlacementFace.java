package dev.twme.vanillashape.common;

/** Face of the supporting block that was clicked for placement. */
public enum PlacementFace {
    NORTH,
    EAST,
    SOUTH,
    WEST,
    UP,
    DOWN;

    public boolean horizontal() {
        return this != UP && this != DOWN;
    }

    public Direction horizontalDirection() {
        if (!horizontal()) throw new IllegalStateException(this + " is not horizontal");
        return Direction.valueOf(name());
    }
}
