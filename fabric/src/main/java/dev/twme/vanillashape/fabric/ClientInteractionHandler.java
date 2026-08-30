package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.PlacementFace;
import dev.twme.vanillashape.common.WireProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

public final class ClientInteractionHandler {
    private static final double EDITOR_TRACE_RANGE = 512;
    private static ClientBlockStore blocks;

    private ClientInteractionHandler() {}

    static void initialize(final ClientBlockStore value) {
        blocks = value;
    }

    public static boolean attack(final Minecraft client) {
        if (client.player == null) return false;
        final ItemStack held = client.player.getMainHandItem();
        final ClientBlockStore.Hit hit = hit(client, held.is(Items.DEBUG_STICK)
                ? client.player.blockInteractionRange() : EDITOR_TRACE_RANGE);
        if (hit == null) return false;
        if (held.is(Items.DEBUG_STICK)) {
            send(WireProtocol.debugSelect(hit.block().x(), hit.block().y(), hit.block().z(),
                    client.player.isShiftKeyDown()));
        } else {
            send(WireProtocol.editorLeftClick(hit.block().x(), hit.block().y(), hit.block().z(),
                    PlacementFace.valueOf(hit.face().name()),
                    local(hit.location().x - hit.block().x()),
                    local(hit.location().y - hit.block().y()),
                    local(hit.location().z - hit.block().z())));
        }
        return true;
    }

    public static boolean use(final Minecraft client) {
        if (client.player == null) return false;
        final ItemStack held = client.player.getMainHandItem();
        final ClientBlockStore.Hit hit = hit(client, held.is(Items.DEBUG_STICK)
                ? client.player.blockInteractionRange() : EDITOR_TRACE_RANGE);
        if (hit == null) return false;
        if (held.is(Items.DEBUG_STICK)) {
            send(WireProtocol.debugCycle(hit.block().x(), hit.block().y(), hit.block().z(),
                    client.player.isShiftKeyDown()));
            return true;
        }
        // Always let Paper ask WorldEdit first. This also permits tools bound to
        // the vanilla item underlying a VanillaShape inventory item; Paper falls
        // back to shape placement or ordinary block interaction when no tool is active.
        send(WireProtocol.editorRightClick(hit.block().x(), hit.block().y(), hit.block().z(),
                PlacementFace.valueOf(hit.face().name()),
                local(hit.location().x - hit.block().x()),
                local(hit.location().y - hit.block().y()),
                local(hit.location().z - hit.block().z())));
        return true;
    }

    public static boolean pick(final Minecraft client) {
        if (client.player == null) return false;
        final ClientBlockStore.Hit hit = hit(client, client.player.blockInteractionRange());
        if (hit == null) return false;
        send(WireProtocol.pickItem(hit.block().x(), hit.block().y(), hit.block().z()));
        return true;
    }

    static ClientBlockStore.Hit hit(final Minecraft client, final double range) {
        if (blocks == null || client.player == null || client.level == null) return null;
        final String world = MinecraftCompat.worldId(client.level);
        final var custom = blocks.raycast(world, client.player.getEyePosition(),
                client.player.getViewVector(1), range);
        if (custom == null) return null;
        final var start = client.player.getEyePosition();
        final var end = start.add(client.player.getViewVector(1).normalize().scale(range));
        final HitResult vanillaBlock = client.level.clip(new ClipContext(start, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (vanillaBlock.getType() != HitResult.Type.MISS
                && start.distanceToSqr(vanillaBlock.getLocation()) < custom.distanceSquared()) return null;
        if (client.hitResult != null && client.hitResult.getType() != HitResult.Type.MISS
                && start.distanceToSqr(client.hitResult.getLocation())
                < custom.distanceSquared()) return null;
        return custom;
    }

    static void send(final byte[] data) {
        try {
            if (ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
                ClientPlayNetworking.send(new SyncPayload(data));
            }
        } catch (final RuntimeException ignored) {
            // The paired Paper plugin may be absent or the connection may be closing.
        }
    }

    static float local(final double value) {
        return (float) Math.max(0, Math.min(1, value));
    }
}
