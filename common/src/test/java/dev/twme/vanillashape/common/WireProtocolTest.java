package dev.twme.vanillashape.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireProtocolTest {
    @Test void roundTripsCompleteBlockDataAndState() throws Exception {
        final SpecialBlock original = new SpecialBlock("minecraft:the_nether", -12, 65, 900,
                ShapeType.VERTICAL_SLAB, "minecraft:oak_log[axis=x]", Direction.WEST,
                CornerShape.INNER_RIGHT, SpecialBlock.WATERLOGGED | SpecialBlock.WEST);
        final WireProtocol.Decoded decoded = WireProtocol.decode(WireProtocol.upsert(original));
        assertEquals(WireProtocol.UPSERT, decoded.action());
        assertEquals(original, decoded.block());
    }

    @Test void roundTripsArbitraryModelAndInteraction() throws Exception {
        final SpecialBlock original = new SpecialBlock("minecraft:overworld", 3, 70, -4,
                ShapeType.MODEL, "minecraft:glass", "minecraft:oak_button[face=wall,facing=east,powered=false]",
                Direction.EAST, CornerShape.STRAIGHT, 0);
        assertEquals(original, WireProtocol.decode(WireProtocol.upsert(original)).block());
        final WireProtocol.Decoded interaction = WireProtocol.decode(WireProtocol.interactBlock(3, 70, -4));
        assertEquals(WireProtocol.INTERACT_BLOCK, interaction.action());
        assertEquals(-4, interaction.z());
    }

    @Test void rejectsOtherProtocolVersions() {
        assertThrows(java.io.IOException.class,
                () -> WireProtocol.decode(new byte[] {(byte) 99, WireProtocol.HELLO}));
    }

    @Test void rejectsTrailingPayloadData() {
        final byte[] hello = WireProtocol.hello();
        final byte[] invalid = java.util.Arrays.copyOf(hello, hello.length + 1);
        assertThrows(java.io.IOException.class, () -> WireProtocol.decode(invalid));
    }

    @Test void roundTripsClientEditRequests() throws Exception {
        final WireProtocol.Decoded debug = WireProtocol.decode(WireProtocol.debugCycle(-2, 70, 19, true));
        assertEquals(WireProtocol.DEBUG_CYCLE, debug.action());
        assertEquals(-2, debug.x());
        assertEquals(70, debug.y());
        assertEquals(19, debug.z());
        assertEquals(true, debug.reverse());

        final WireProtocol.Decoded place = WireProtocol.decode(WireProtocol.axiomPlace(
                10, -4, 30, PlacementFace.UP, .75f, 1, .25f));
        assertEquals(WireProtocol.AXIOM_PLACE, place.action());
        assertEquals(30, place.z());
        assertEquals(PlacementFace.UP, place.face());
        assertEquals(.75f, place.hitX());

        final WireProtocol.Decoded broken = WireProtocol.decode(WireProtocol.breakBlock(-8, 12, 45));
        assertEquals(WireProtocol.BREAK_BLOCK, broken.action());
        assertEquals(-8, broken.x());
        assertEquals(12, broken.y());
        assertEquals(45, broken.z());
    }

    @Test void rejectsOutOfRangePlacementHit() {
        assertThrows(IllegalArgumentException.class, () -> WireProtocol.placeItem(
                0, 64, 0, PlacementFace.NORTH, Float.NaN, .5f, 0));
        assertThrows(IllegalArgumentException.class, () -> WireProtocol.placeItem(
                0, 64, 0, PlacementFace.NORTH, 1.1f, .5f, 0));
    }

    @Test void decoderRejectsInvalidPlacementFaceAndHit() {
        final byte[] invalidFace = WireProtocol.placeItem(
                0, 64, 0, PlacementFace.NORTH, .5f, .5f, .5f);
        invalidFace[14] = (byte) 99;
        assertThrows(java.io.IOException.class, () -> WireProtocol.decode(invalidFace));

        final byte[] invalidHit = WireProtocol.placeItem(
                0, 64, 0, PlacementFace.NORTH, .5f, .5f, .5f);
        java.nio.ByteBuffer.wrap(invalidHit).putFloat(15, Float.NaN);
        assertThrows(java.io.IOException.class, () -> WireProtocol.decode(invalidHit));
    }
}
