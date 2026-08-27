package dev.twme.vanillashape.fabric;

import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.Effects;
import com.moulberry.axiomclientapi.IAxiomWorldRenderContext;
import com.moulberry.axiomclientapi.regions.BooleanRegion;
import com.moulberry.axiomclientapi.service.RegionProvider;
import com.moulberry.axiomclientapi.service.ToolService;
import dev.twme.vanillashape.common.WireProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
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
        final BlockPos target = placementTarget();
        if (target != null) {
            ClientInteractionHandler.send(WireProtocol.axiomPlace(
                    target.getX(), target.getY(), target.getZ()));
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
        final BlockPos target = placementTarget();
        if (target == null) return;
        preview.add(target.getX(), target.getY(), target.getZ());
        preview.render(context, Vec3.ZERO, Effects.SELECTION);
    }

    private BlockPos placementTarget() {
        final ClientBlockStore.Hit custom = customTarget();
        if (custom != null) {
            return new BlockPos(custom.block().x(), custom.block().y(), custom.block().z())
                    .relative(custom.face());
        }
        final BlockHitResult vanilla = tools.raycastBlock();
        return vanilla == null ? null : vanilla.getBlockPos().relative(vanilla.getDirection());
    }

    private static ClientBlockStore.Hit customTarget() {
        return ClientInteractionHandler.hit(Minecraft.getInstance(), 512);
    }

    private static <T> T service(final Class<T> type) {
        return ServiceLoader.load(type).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing Axiom service " + type.getSimpleName()));
    }
}
