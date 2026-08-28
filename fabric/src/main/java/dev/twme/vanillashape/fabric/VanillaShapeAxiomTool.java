package dev.twme.vanillashape.fabric;

import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.Effects;
import com.moulberry.axiomclientapi.IAxiomWorldRenderContext;
import com.moulberry.axiomclientapi.regions.BooleanRegion;
import com.moulberry.axiomclientapi.service.RegionProvider;
import com.moulberry.axiomclientapi.service.ToolService;
import dev.twme.vanillashape.common.PlacementFace;
import dev.twme.vanillashape.common.WireProtocol;
import net.minecraft.world.phys.Vec3;

import java.util.ServiceLoader;

/** Official AxiomClientAPI tool for placing, replacing and deleting virtual blocks. */
final class VanillaShapeAxiomTool implements CustomTool {
    private final ToolService tools = service(ToolService.class);
    private final BooleanRegion preview = service(RegionProvider.class).createBoolean();

    @Override public String name() {
        return "VanillaShape";
    }

    @Override public void reset() {
        preview.clear();
    }

    @Override public boolean callUseTool() {
        final AxiomTargeting.Placement target = AxiomTargeting.placement(tools);
        if (target != null) {
            ClientInteractionHandler.send(WireProtocol.axiomPlace(
                    target.position().getX(), target.position().getY(), target.position().getZ(),
                    PlacementFace.valueOf(target.surface().face().name()),
                    ClientInteractionHandler.local(target.surface().hit().x - target.surface().support().getX()),
                    ClientInteractionHandler.local(target.surface().hit().y - target.surface().support().getY()),
                    ClientInteractionHandler.local(target.surface().hit().z - target.surface().support().getZ())));
        }
        return true;
    }

    @Override public boolean callConfirm() {
        final ClientBlockStore.Hit target = customTarget();
        if (target != null) {
            ClientInteractionHandler.send(WireProtocol.axiomReplace(
                    target.block().x(), target.block().y(), target.block().z()));
        }
        return true;
    }

    @Override public boolean callDelete() {
        final ClientBlockStore.Hit target = customTarget();
        if (target != null) {
            ClientInteractionHandler.send(WireProtocol.axiomDelete(
                    target.block().x(), target.block().y(), target.block().z()));
        }
        return true;
    }

    @Override public void render(final IAxiomWorldRenderContext context) {
        preview.clear();
        final AxiomTargeting.Placement target = AxiomTargeting.placement(tools);
        if (target == null) return;
        preview.add(target.position().getX(), target.position().getY(), target.position().getZ());
        preview.render(context, Vec3.ZERO, Effects.SELECTION);
    }

    private ClientBlockStore.Hit customTarget() {
        final AxiomTargeting.Surface surface = AxiomTargeting.surface(tools);
        if (surface == null) return null;
        final var client = net.minecraft.client.Minecraft.getInstance();
        final ClientBlockStore.Hit custom = ClientInteractionHandler.hit(client, 512);
        return custom != null && custom.block().x() == surface.support().getX()
                && custom.block().y() == surface.support().getY()
                && custom.block().z() == surface.support().getZ() ? custom : null;
    }

    private static <T> T service(final Class<T> type) {
        return ServiceLoader.load(type).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing Axiom service " + type.getSimpleName()));
    }
}
