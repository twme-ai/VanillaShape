package dev.twme.vanillashape.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Registers rendering through the classic Fabric world-render event API. */
public final class LegacyRenderBridge {
    private LegacyRenderBridge() {}

    static void initialize(final ShapeRenderer renderer) {
        WorldRenderEvents.AFTER_ENTITIES.register(renderer::render);
    }
}
