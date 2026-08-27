package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.WireProtocol;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClientBlockStoreTest {
    @Test void raycastHitsRenderedGeometryAndReturnsItsFace() throws Exception {
        final ClientBlockStore store = new ClientBlockStore();
        store.accept(WireProtocol.upsert(new SpecialBlock("minecraft:overworld", 0, 64, 0,
                ShapeType.VERTICAL_SLAB, "minecraft:stone", Direction.NORTH,
                CornerShape.STRAIGHT, 0)));

        final ClientBlockStore.Hit hit = store.raycast("minecraft:overworld",
                new Vec3(.5, 64.5, -2), new Vec3(0, 0, 1), 5);

        assertNotNull(hit);
        assertEquals(ShapeType.VERTICAL_SLAB, hit.block().shape());
        assertEquals(net.minecraft.core.Direction.NORTH, hit.face());
        assertEquals(4.0, hit.distanceSquared(), 0.0001);
    }
}
