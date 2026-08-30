package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.WireProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VanillaShapeClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VanillaShape");

    @Override public void onInitializeClient() {
        final ClientBlockStore blocks = new ClientBlockStore();
        final ShapeRenderer renderer = new ShapeRenderer(blocks);
        ClientInteractionHandler.initialize(blocks);

        PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
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
        LevelRenderEvents.COLLECT_SUBMITS.register(renderer::render);
        if (FabricLoader.getInstance().isModLoaded("axiom") && supportsAxiom()) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> AxiomIntegration.initialize(LOGGER, blocks));
        }
        LOGGER.info("VanillaShape client renderer initialized (no resource pack required).");
    }

    private static boolean supportsAxiom() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .filter("26.2"::equals).isPresent();
    }
}
