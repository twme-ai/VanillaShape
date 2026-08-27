package dev.twme.vanillashape.common;

import java.util.function.BiFunction;

/** Pure relative-state rules shared by tests and the authoritative server. */
public final class ConnectionResolver {
    private ConnectionResolver() {}

    public static CornerShape corner(
            final SpecialBlock self,
            final BiFunction<Integer, Integer, SpecialBlock> horizontalLookup) {
        if (self.shape() != ShapeType.STAIRS && self.shape() != ShapeType.VERTICAL_SLAB) {
            return CornerShape.STRAIGHT;
        }

        final Direction facing = self.facing();
        final SpecialBlock front = horizontalLookup.apply(facing.dx(), facing.dz());
        if (compatible(self, front) && facing.perpendicular(front.facing())
                && differentOrientationAt(self, horizontalLookup,
                    front.facing().opposite().dx(), front.facing().opposite().dz())) {
            return front.facing() == facing.left()
                    ? CornerShape.OUTER_LEFT : CornerShape.OUTER_RIGHT;
        }

        final Direction backDirection = facing.opposite();
        final SpecialBlock back = horizontalLookup.apply(backDirection.dx(), backDirection.dz());
        if (compatible(self, back) && facing.perpendicular(back.facing())
                && differentOrientationAt(self, horizontalLookup,
                    back.facing().dx(), back.facing().dz())) {
            return back.facing() == facing.left()
                    ? CornerShape.INNER_LEFT : CornerShape.INNER_RIGHT;
        }
        return CornerShape.STRAIGHT;
    }

    private static boolean compatible(final SpecialBlock self, final SpecialBlock other) {
        if (other == null || self.shape() != other.shape()) return false;
        return self.shape() != ShapeType.STAIRS
                || ((self.flags() & SpecialBlock.TOP) == (other.flags() & SpecialBlock.TOP));
    }

    private static boolean differentOrientationAt(
            final SpecialBlock self,
            final BiFunction<Integer, Integer, SpecialBlock> lookup,
            final int dx,
            final int dz) {
        final SpecialBlock side = lookup.apply(dx, dz);
        return !compatible(self, side) || side.facing() != self.facing();
    }
}
