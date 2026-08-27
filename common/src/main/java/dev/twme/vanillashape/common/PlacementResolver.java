package dev.twme.vanillashape.common;

/** Pure placement rules shared by item, command and Axiom placement paths. */
public final class PlacementResolver {
    private static final float CENTER_DEAD_ZONE = 0.20f;

    private PlacementResolver() {}

    /** Converts Minecraft/Paper yaw (0=south, 90=west) to a cardinal direction. */
    public static Direction playerFacing(final float yawDegrees) {
        if (!Float.isFinite(yawDegrees)) return Direction.SOUTH;
        final int quadrant = Math.floorMod(Math.round(yawDegrees / 90.0f), 4);
        return switch (quadrant) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    /**
     * Resolves the occupied half of a vertical slab.
     *
     * <p>A horizontal outward face of the support places the slab against that support. On a top or bottom
     * face, clicking clearly toward an edge selects that half; the central area follows the
     * player's look direction.</p>
     */
    public static Direction verticalSlabFacing(
            final Direction playerFacing, final PlacementFace clickedFace,
            final float hitX, final float hitZ) {
        if (clickedFace.horizontal()) return clickedFace.horizontalDirection().opposite();

        final float x = clamp(hitX) - .5f;
        final float z = clamp(hitZ) - .5f;
        final float absX = Math.abs(x);
        final float absZ = Math.abs(z);
        if (Math.max(absX, absZ) <= CENTER_DEAD_ZONE
                || Math.abs(absX - absZ) < .0001f) return playerFacing;
        if (absX > absZ) return x < 0 ? Direction.WEST : Direction.EAST;
        return z < 0 ? Direction.NORTH : Direction.SOUTH;
    }

    public static SpecialBlock forPlacement(
            final SpecialBlock template, final Direction playerFacing,
            final PlacementFace clickedFace, final float hitX, final float hitY, final float hitZ) {
        if (template.shape() != ShapeType.VERTICAL_SLAB) return template;
        return template.withFacing(verticalSlabFacing(playerFacing, clickedFace, hitX, hitZ))
                .withCorner(CornerShape.STRAIGHT);
    }

    private static float clamp(final float value) {
        if (!Float.isFinite(value)) return .5f;
        return Math.max(0, Math.min(1, value));
    }
}
