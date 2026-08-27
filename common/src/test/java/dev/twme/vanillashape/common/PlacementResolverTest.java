package dev.twme.vanillashape.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementResolverTest {
    @Test void mapsMinecraftYawToCardinalPlayerDirection() {
        assertEquals(Direction.SOUTH, PlacementResolver.playerFacing(0));
        assertEquals(Direction.WEST, PlacementResolver.playerFacing(90));
        assertEquals(Direction.NORTH, PlacementResolver.playerFacing(180));
        assertEquals(Direction.EAST, PlacementResolver.playerFacing(-90));
        assertEquals(Direction.SOUTH, PlacementResolver.playerFacing(360));
    }

    @Test void horizontalFacePlacesVerticalSlabAgainstSupport() {
        assertEquals(Direction.NORTH, PlacementResolver.verticalSlabFacing(
                Direction.EAST, PlacementFace.SOUTH, .5f, 1));
        assertEquals(Direction.WEST, PlacementResolver.verticalSlabFacing(
                Direction.NORTH, PlacementFace.EAST, 1, .5f));
        assertEquals(Direction.SOUTH, PlacementResolver.verticalSlabFacing(
                Direction.WEST, PlacementFace.NORTH, .5f, 0));
        assertEquals(Direction.EAST, PlacementResolver.verticalSlabFacing(
                Direction.SOUTH, PlacementFace.WEST, 0, .5f));
    }

    @Test void topFaceUsesClearlyClickedHalf() {
        assertEquals(Direction.EAST, PlacementResolver.verticalSlabFacing(
                Direction.NORTH, PlacementFace.UP, .9f, .55f));
        assertEquals(Direction.NORTH, PlacementResolver.verticalSlabFacing(
                Direction.SOUTH, PlacementFace.UP, .45f, .05f));
    }

    @Test void topFaceCenterFallsBackToPlayerDirection() {
        assertEquals(Direction.SOUTH, PlacementResolver.verticalSlabFacing(
                Direction.SOUTH, PlacementFace.UP, .5f, .5f));
    }

    @Test void placementResetsStaleItemCornerBeforeAutomaticConnections() {
        final SpecialBlock item = new SpecialBlock("minecraft:overworld", 0, 0, 0,
                ShapeType.VERTICAL_SLAB, "minecraft:stone", Direction.WEST,
                CornerShape.INNER_LEFT, SpecialBlock.WATERLOGGED);
        final SpecialBlock placed = PlacementResolver.forPlacement(
                item, Direction.EAST, PlacementFace.UP, .5f, 1, .5f);
        assertEquals(Direction.EAST, placed.facing());
        assertEquals(CornerShape.STRAIGHT, placed.corner());
        assertEquals(SpecialBlock.WATERLOGGED, placed.flags());
    }

    @Test void otherShapesKeepTheirExplicitItemState() {
        final SpecialBlock stairs = new SpecialBlock("minecraft:overworld", 0, 0, 0,
                ShapeType.STAIRS, "minecraft:stone", Direction.WEST,
                CornerShape.INNER_LEFT, SpecialBlock.TOP);
        assertEquals(stairs, PlacementResolver.forPlacement(
                stairs, Direction.EAST, PlacementFace.UP, .9f, 1, .5f));
    }
}
