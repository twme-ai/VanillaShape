package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.ShapeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldEditProxyCodecTest {
    @Test void parsesFriendlyNamedStateFieldsAndShapeOnlyMasks() {
        final WorldEditBlockSpec exact = WorldEditBlockSpec.parse(
                "vanillashape:stairs{material:\"minecraft:oak_log[axis=x]\",facing:\"east\","
                        + "half:\"top\",corner:\"outer_left\",waterlogged:1b}", value -> value,
                "minecraft:air");
        assertEquals(ShapeType.STAIRS, exact.template().shape());
        assertEquals(Direction.EAST, exact.template().facing());
        assertEquals(CornerShape.OUTER_LEFT, exact.template().corner());
        assertTrue((exact.template().flags() & SpecialBlock.TOP) != 0);
        assertTrue((exact.template().flags() & SpecialBlock.WATERLOGGED) != 0);
        assertTrue(exact.matches(exact.template()));
        assertFalse(exact.matches(exact.template().withFacing(Direction.NORTH)));

        final WorldEditBlockSpec shapeOnly = WorldEditBlockSpec.parse(
                "vanillashape:stairs", value -> value, "minecraft:air");
        assertTrue(shapeOnly.matches(exact.template()));
    }

    @Test void acceptsFaweBracketWrappedState() {
        final WorldEditBlockSpec spec = WorldEditBlockSpec.parse(
                "vanillashape:stairs[{material:\"minecraft:oak_log[axis=x]\","
                        + "facing:\"east\",half:\"top\"}]", value -> value, "minecraft:air");

        assertEquals(ShapeType.STAIRS, spec.template().shape());
        assertEquals("minecraft:oak_log[axis=x]", spec.template().material());
        assertEquals(Direction.EAST, spec.template().facing());
        assertTrue((spec.template().flags() & SpecialBlock.TOP) != 0);
    }

    @Test void extractsWorldEditCopySourceMaskWithoutEatingLaterFlags() {
        assertNull(WorldEditIntegration.sourceMask("//copy -e"));
        assertEquals("vanillashape:stairs[{material:\"minecraft:oak_log[axis=x]\"}]",
                WorldEditIntegration.sourceMask("//copy -e -m "
                        + "vanillashape:stairs[{material:\"minecraft:oak_log[axis=x]\"}] -b"));
    }

    @Test void parsesGenericModelAndKeepsShapeOnlyMaskGeneric() {
        final WorldEditBlockSpec exact = WorldEditBlockSpec.parse(
                "vanillashape:model{model:\"minecraft:oak_button[face=wall,facing=east,powered=false]\","
                        + "material:\"minecraft:glass\"}", value -> value, "minecraft:air");
        assertEquals(ShapeType.MODEL, exact.template().shape());
        assertEquals("minecraft:oak_button[face=wall,facing=east,powered=false]", exact.template().model());
        assertTrue(exact.matches(exact.template()));
        assertFalse(exact.matches(exact.template().withModel("minecraft:vine[north=true]")));
        final WorldEditBlockSpec shapeOnly = WorldEditBlockSpec.parse("vanillashape:model", value -> value,
                "minecraft:air");
        assertTrue(shapeOnly.matches(exact.template()));

    }

    @Test void usesHeldMaterialOnlyWhenStateDoesNotExplicitlyNameOne() {
        final WorldEditBlockSpec held = WorldEditBlockSpec.parse("vanillashape:wall", value -> value,
                "minecraft:oak_log[axis=x]");
        assertEquals("minecraft:oak_log[axis=x]", held.template().material());
        assertFalse(held.materialSpecified());

        final WorldEditBlockSpec explicit = WorldEditBlockSpec.parse(
                "vanillashape:wall{material:\"minecraft:warped_planks\"}", value -> value,
                "minecraft:oak_log[axis=x]");
        assertEquals("minecraft:warped_planks", explicit.template().material());
        assertTrue(explicit.materialSpecified());
    }
}
