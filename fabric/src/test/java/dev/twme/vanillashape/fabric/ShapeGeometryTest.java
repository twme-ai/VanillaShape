package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShapeGeometryTest {
    private static SpecialBlock vertical(final Direction facing, final CornerShape corner) {
        return new SpecialBlock("minecraft:overworld", 0, 0, 0, ShapeType.VERTICAL_SLAB,
                "minecraft:stone", facing, corner, 0);
    }

    @Test void straightVerticalSlabOccupiesHalfBlock() {
        final var boxes = ShapeGeometry.boxes(vertical(Direction.NORTH, CornerShape.STRAIGHT));
        assertEquals(1, boxes.size());
        assertEquals(.5f, boxes.getFirst().maxZ());
        assertEquals(1f, boxes.getFirst().maxY());
    }

    @Test void innerCornerHasThreeQuarterFootprint() {
        final var boxes = ShapeGeometry.boxes(vertical(Direction.NORTH, CornerShape.INNER_LEFT));
        final double area = boxes.stream().mapToDouble(box ->
                (box.maxX() - box.minX()) * (box.maxZ() - box.minZ())).sum();
        assertEquals(.75, area, 0.0001);
    }

    @Test void eastFacingRotatesTheHalf() {
        final var box = ShapeGeometry.boxes(vertical(Direction.EAST, CornerShape.STRAIGHT)).getFirst();
        assertEquals(.5f, box.minX());
        assertEquals(1f, box.maxZ());
    }
}
