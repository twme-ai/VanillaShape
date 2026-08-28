package dev.twme.vanillashape.fabric;

import com.moulberry.axiomclientapi.service.ToolRegistryService;
import org.slf4j.Logger;

import java.util.ServiceLoader;

/** Isolated optional entry point so Axiom API classes are never loaded when Axiom is absent. */
final class AxiomIntegration {
    private AxiomIntegration() {}

    static void initialize(final Logger logger, final ClientBlockStore blocks) {
        try {
            final ToolRegistryService registry = ServiceLoader.load(ToolRegistryService.class).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Axiom ToolRegistryService is unavailable"));
            registry.register(new VanillaShapeAxiomTool());
            registry.register(new VanillaShapeAxiomClipboardTool(blocks));
            logger.info("Registered the VanillaShape edit and clipboard tools in Axiom Editor.");
        } catch (final Throwable error) {
            logger.warn("Axiom is installed, but its VanillaShape tool could not be registered", error);
        }
    }
}
