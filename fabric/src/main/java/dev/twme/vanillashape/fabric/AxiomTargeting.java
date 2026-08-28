package dev.twme.vanillashape.fabric;

import com.moulberry.axiomclientapi.service.ToolService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Chooses the nearest surface across Axiom's vanilla raycast and VanillaShape geometry. */
final class AxiomTargeting {
    record Surface(BlockPos support, Direction face, Vec3 hit) {}
    record Placement(BlockPos position, Surface surface) {}

    private AxiomTargeting() {}

    static Surface surface(final ToolService tools) {
        final Minecraft client = Minecraft.getInstance();
        if (client.player == null) return null;
        final ClientBlockStore.Hit custom = ClientInteractionHandler.hit(client, 512);
        final BlockHitResult vanilla = tools.raycastBlock();
        if (custom == null && vanilla == null) return null;
        if (custom != null && (vanilla == null || custom.distanceSquared()
                <= client.player.getEyePosition().distanceToSqr(vanilla.getLocation()))) {
            return new Surface(new BlockPos(custom.block().x(), custom.block().y(), custom.block().z()),
                    custom.face(), custom.location());
        }
        return new Surface(vanilla.getBlockPos(), vanilla.getDirection(), vanilla.getLocation());
    }

    static Placement placement(final ToolService tools) {
        final Surface surface = surface(tools);
        return surface == null ? null : new Placement(surface.support().relative(surface.face()), surface);
    }

    static String world() {
        final Minecraft client = Minecraft.getInstance();
        return client.level == null ? null : client.level.dimension().identifier().toString();
    }
}
