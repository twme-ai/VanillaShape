package dev.twme.vanillashape.common;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionResolverTest {
    private static SpecialBlock slab(final int x, final int z, final Direction facing) {
        return new SpecialBlock("minecraft:overworld", x, 64, z, ShapeType.VERTICAL_SLAB,
                "minecraft:stone", facing, CornerShape.STRAIGHT, 0);
    }

    @Test void derivesOuterLeftLikeVanillaStairs() {
        final SpecialBlock self = slab(0, 0, Direction.NORTH);
        final Map<String, SpecialBlock> neighbors = Map.of("0,-1", slab(0, -1, Direction.WEST));
        assertEquals(CornerShape.OUTER_LEFT, ConnectionResolver.corner(self,
                (dx, dz) -> neighbors.get(dx + "," + dz)));
    }

    @Test void derivesInnerRightLikeVanillaStairs() {
        final SpecialBlock self = slab(0, 0, Direction.NORTH);
        final Map<String, SpecialBlock> neighbors = new HashMap<>();
        neighbors.put("0,1", slab(0, 1, Direction.EAST));
        assertEquals(CornerShape.INNER_RIGHT, ConnectionResolver.corner(self,
                (dx, dz) -> neighbors.get(dx + "," + dz)));
    }

    @Test void parallelNeighborKeepsStraightState() {
        final SpecialBlock self = slab(0, 0, Direction.NORTH);
        assertEquals(CornerShape.STRAIGHT, ConnectionResolver.corner(self,
                (dx, dz) -> dz == -1 ? slab(dx, dz, Direction.NORTH) : null));
    }
}
