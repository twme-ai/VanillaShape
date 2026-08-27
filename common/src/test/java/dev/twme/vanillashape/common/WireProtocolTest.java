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
}
