package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.PlacementFace;
import dev.twme.vanillashape.common.WireProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.HitResult;

public final class ClientInteractionHandler {
    private static ClientBlockStore blocks;

    private ClientInteractionHandler() {}

    static void initialize(final ClientBlockStore value) {
        blocks = value;
    }

    public static boolean attack(final Minecraft client) {
        if (client.player == null || !client.player.getMainHandItem().is(Items.DEBUG_STICK)) return false;
        final ClientBlockStore.Hit hit = hit(client, client.player.blockInteractionRange());
        if (hit == null) return false;
        send(WireProtocol.debugSelect(hit.block().x(), hit.block().y(), hit.block().z(),
                client.player.isShiftKeyDown()));
        return true;
    }

    public static boolean use(final Minecraft client) {
        if (client.player == null) return false;
        final ClientBlockStore.Hit hit = hit(client, client.player.blockInteractionRange());
        if (hit == null) return false;
        final ItemStack held = client.player.getMainHandItem();
        if (held.is(Items.DEBUG_STICK)) {
            send(WireProtocol.debugCycle(hit.block().x(), hit.block().y(), hit.block().z(),
                    client.player.isShiftKeyDown()));
            return true;
        }
        if (!isShapeItem(held)) return false;
        final BlockPos support = new BlockPos(hit.block().x(), hit.block().y(), hit.block().z());
        final BlockPos target = support.relative(hit.face());
        send(WireProtocol.placeItem(target.getX(), target.getY(), target.getZ(),
                PlacementFace.valueOf(hit.face().name()),
                local(hit.location().x - support.getX()),
                local(hit.location().y - support.getY()),
                local(hit.location().z - support.getZ())));
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
        final String world = client.level.dimension().identifier().toString();
        final var custom = blocks.raycast(world, client.player.getEyePosition(),
                client.player.getViewVector(1), range);
        if (custom == null) return null;
        if (client.hitResult != null && client.hitResult.getType() != HitResult.Type.MISS
                && client.player.getEyePosition().distanceToSqr(client.hitResult.getLocation())
                < custom.distanceSquared()) return null;
        return custom;
    }

    static boolean isShapeItem(final ItemStack item) {
        final CustomData data = item.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getCompoundOrEmpty("PublicBukkitValues")
                .contains("vanillashape:item_version");
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
