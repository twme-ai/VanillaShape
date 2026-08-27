package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test void closedFenceGateMatchesVanillaEightElementModel() {
        final SpecialBlock gate = new SpecialBlock("minecraft:overworld", 0, 0, 0,
                ShapeType.FENCE_GATE, "minecraft:oak_planks", Direction.NORTH,
                CornerShape.STRAIGHT, 0);
        final var boxes = ShapeGeometry.boxes(gate);
        assertEquals(8, boxes.size());
        assertTrue(boxes.stream().allMatch(box -> box.minZ() == 7 / 16f && box.maxZ() == 9 / 16f));
        assertEquals(2, boxes.stream().filter(box -> box.maxY() == 1).count());
    }

    @Test void openFenceGateFoldsFourRailsBesideItsPosts() {
        final SpecialBlock gate = new SpecialBlock("minecraft:overworld", 0, 0, 0,
                ShapeType.FENCE_GATE, "minecraft:oak_planks", Direction.NORTH,
                CornerShape.STRAIGHT, SpecialBlock.OPEN);
        final var boxes = ShapeGeometry.boxes(gate);
        assertEquals(8, boxes.size());
        assertEquals(4, boxes.stream().filter(box -> box.minZ() == 9 / 16f
                && box.maxZ() == 13 / 16f).count());
        assertTrue(boxes.stream().noneMatch(box -> box.maxX() - box.minX() > 2 / 16f));
    }

    @Test void wallUsesIndependentVanillaPostLowAndTallStates() {
        final int flags = SpecialBlock.WALL_UP | SpecialBlock.NORTH | SpecialBlock.SOUTH
                | SpecialBlock.WALL_TALL_SOUTH;
        final SpecialBlock wall = new SpecialBlock("minecraft:overworld", 0, 0, 0,
                ShapeType.WALL, "minecraft:stone", Direction.NORTH, CornerShape.STRAIGHT, flags);
        final var boxes = ShapeGeometry.boxes(wall);
        assertEquals(3, boxes.size());
        assertEquals(1, boxes.stream().filter(box -> box.minX() == .25f && box.maxX() == .75f).count());
        assertEquals(.875f, boxes.stream().filter(box -> box.minZ() == 0).findFirst().orElseThrow().maxY());
        assertEquals(1f, boxes.stream().filter(box -> box.maxZ() == 1).findFirst().orElseThrow().maxY());
    }

    @Test void unionSurfaceBuilderRemovesOverlappingInteriorFaces() {
        final SpecialBlock stair = new SpecialBlock("minecraft:overworld", 0, 0, 0,
                ShapeType.STAIRS, "minecraft:glass", Direction.NORTH, CornerShape.STRAIGHT, 0);
        final var surfaces = ShapeGeometry.surfaces(stair);
        assertTrue(surfaces.stream().noneMatch(face -> face.direction() == net.minecraft.core.Direction.UP
                && face.minY() == .5f && face.maxX() <= 1 && face.maxZ() <= .5f));
    }

    @Test void unionSurfaceBuilderKeepsExteriorFacesBetweenSeparateShapes() {
        final SpecialBlock slab = new SpecialBlock("minecraft:overworld", 0, 0, 0,
                ShapeType.SLAB, "minecraft:glass", Direction.NORTH, CornerShape.STRAIGHT, 0);
        final var surfaces = ShapeGeometry.surfaces(slab);
        assertTrue(surfaces.stream().anyMatch(face -> face.direction() == net.minecraft.core.Direction.EAST));
    }
}
