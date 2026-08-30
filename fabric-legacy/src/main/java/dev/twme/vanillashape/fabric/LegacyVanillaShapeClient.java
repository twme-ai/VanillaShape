package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.WireProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client bootstrap for obfuscated Minecraft releases through 1.21.11. */
public final class LegacyVanillaShapeClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VanillaShape");

    @Override public void onInitializeClient() {
        final ClientBlockStore blocks = new ClientBlockStore();
        final ShapeRenderer renderer = new ShapeRenderer(blocks);
        LegacyRenderBridge.initialize(renderer);
        ClientInteractionHandler.initialize(blocks);

        PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SyncPayload.TYPE, SyncPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> {
            try {
                blocks.accept(payload.data());
            } catch (final Exception error) {
                LOGGER.warn("Rejected malformed VanillaShape payload", error);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            blocks.clear();
            renderer.clearMaterials();
            try {
                ClientPlayNetworking.send(new SyncPayload(WireProtocol.hello()));
            } catch (final RuntimeException error) {
                LOGGER.debug("Server does not accept the VanillaShape channel");
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> blocks.clear());
        LOGGER.info("VanillaShape legacy client renderer initialized (no resource pack required).");
    }
}
