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

        final WireProtocol.Decoded place = WireProtocol.decode(WireProtocol.axiomPlace(10, -4, 30));
        assertEquals(WireProtocol.AXIOM_PLACE, place.action());
        assertEquals(30, place.z());
    }
}
