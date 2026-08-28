package dev.twme.vanillashape.fabric;

import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.Effects;
import com.moulberry.axiomclientapi.IAxiomWorldRenderContext;
import com.moulberry.axiomclientapi.regions.BooleanRegion;
import com.moulberry.axiomclientapi.service.RegionProvider;
import com.moulberry.axiomclientapi.service.ToolService;
import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.WireProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Axiom-native two-corner clipboard for Paper-authoritative virtual blocks. */
final class VanillaShapeAxiomClipboardTool implements CustomTool {
    private static final long MAX_SELECTION_VOLUME = 16_777_216L;

    private final ClientBlockStore store;
    private final ToolService tools = service(ToolService.class);
    private final BooleanRegion preview = service(RegionProvider.class).createBoolean();
    private BlockPos first;
    private BlockPos second;
    private BlockPos pasteOrigin;
    private List<BlockPos> copiedOffsets = List.of();
    private String stateWorld;
    private boolean pasteMode;

    VanillaShapeAxiomClipboardTool(final ClientBlockStore store) {
        this.store = store;
    }

    @Override public String name() {
        return "VanillaShape Clipboard";
    }

    @Override public void reset() {
        preview.clear();
    }

    @Override public boolean callUseTool() {
        ensureWorld();
        if (pasteMode) {
            final AxiomTargeting.Placement target = AxiomTargeting.placement(tools);
            if (target != null) {
                pasteOrigin = target.position();
                message("Paste origin: " + coordinates(pasteOrigin) + ". Press Enter to paste.");
            }
            return true;
        }

        final AxiomTargeting.Surface target = AxiomTargeting.surface(tools);
        if (target == null) return true;
        if (first == null) {
            first = target.support();
            message("First corner: " + coordinates(first) + ". Right-click the opposite corner.");
        } else if (second == null) {
            second = target.support();
            message("Second corner: " + coordinates(second) + ". Press Enter to copy.");
        } else {
            first = target.support();
            second = null;
            message("Started a new selection at " + coordinates(first) + ".");
        }
        return true;
    }

    @Override public boolean callConfirm() {
        ensureWorld();
        if (pasteMode) {
            if (pasteOrigin == null) {
                final AxiomTargeting.Placement target = AxiomTargeting.placement(tools);
                if (target != null) pasteOrigin = target.position();
            }
            if (pasteOrigin == null) {
                message("Right-click a surface to choose the paste origin first.");
                return true;
            }
            ClientInteractionHandler.send(WireProtocol.axiomPaste(
                    pasteOrigin.getX(), pasteOrigin.getY(), pasteOrigin.getZ()));
            return true;
        }

        if (first == null || second == null) {
            message("Right-click two corners before copying.");
            return true;
        }
        final Bounds bounds = Bounds.of(first, second);
        if (bounds.volume() > MAX_SELECTION_VOLUME) {
            message("That selection is too large for the VanillaShape clipboard.");
            return true;
        }
        final List<BlockPos> offsets = new ArrayList<>();
        for (final SpecialBlock block : store.blocks(stateWorld)) {
            if (!bounds.contains(block.x(), block.y(), block.z())) continue;
            offsets.add(new BlockPos(block.x() - bounds.minX, block.y() - bounds.minY,
                    block.z() - bounds.minZ));
        }
        if (offsets.isEmpty()) {
            message("That selection contains no VanillaShape blocks.");
            return true;
        }
        ClientInteractionHandler.send(WireProtocol.axiomCopy(
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ));
        copiedOffsets = List.copyOf(offsets);
        pasteMode = true;
        pasteOrigin = null;
        message("Copied " + offsets.size() + " block(s). Right-click a paste origin, then press Enter.");
        return true;
    }

    @Override public boolean callDelete() {
        clearState();
        message("VanillaShape clipboard selection cleared.");
        return true;
    }

    @Override public void render(final IAxiomWorldRenderContext context) {
        ensureWorld();
        preview.clear();
        if (pasteMode && pasteOrigin != null) {
            for (final BlockPos offset : copiedOffsets) {
                preview.add(pasteOrigin.getX() + offset.getX(), pasteOrigin.getY() + offset.getY(),
                        pasteOrigin.getZ() + offset.getZ());
            }
        } else {
            if (first != null) preview.add(first.getX(), first.getY(), first.getZ());
            if (second != null) {
                preview.add(second.getX(), second.getY(), second.getZ());
                final Bounds bounds = Bounds.of(first, second);
                for (final SpecialBlock block : store.blocks(stateWorld)) {
                    if (bounds.contains(block.x(), block.y(), block.z())) {
                        preview.add(block.x(), block.y(), block.z());
                    }
                }
            }
        }
        preview.render(context, Vec3.ZERO, Effects.SELECTION);
    }

    private void ensureWorld() {
        final String current = AxiomTargeting.world();
        if (java.util.Objects.equals(current, stateWorld)) return;
        clearState();
        stateWorld = current;
    }

    private void clearState() {
        first = null;
        second = null;
        pasteOrigin = null;
        copiedOffsets = List.of();
        pasteMode = false;
        preview.clear();
    }

    private static String coordinates(final BlockPos position) {
        return position.getX() + ", " + position.getY() + ", " + position.getZ();
    }

    private static void message(final String text) {
        final Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.sendOverlayMessage(Component.literal(text));
    }

    private static <T> T service(final Class<T> type) {
        return ServiceLoader.load(type).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing Axiom service " + type.getSimpleName()));
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds of(final BlockPos first, final BlockPos second) {
            return new Bounds(Math.min(first.getX(), second.getX()),
                    Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()),
                    Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                    Math.max(first.getZ(), second.getZ()));
        }

        long volume() {
            final long x = (long) maxX - minX + 1;
            final long y = (long) maxY - minY + 1;
            final long z = (long) maxZ - minZ + 1;
            try {
                return Math.multiplyExact(Math.multiplyExact(x, y), z);
            } catch (final ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        boolean contains(final int x, final int y, final int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }
}
