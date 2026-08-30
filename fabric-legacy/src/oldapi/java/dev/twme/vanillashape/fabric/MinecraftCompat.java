package dev.twme.vanillashape.fabric;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class MinecraftCompat {
    private MinecraftCompat() {}

    static String worldId(final ClientLevel level) {
        return level.dimension().location().toString();
    }

    static Vec3 cameraPosition(final Camera camera) {
        return camera.getPosition();
    }

    static boolean isTranslucent(final BlockState state) {
        return ItemBlockRenderTypes.getChunkRenderType(state) == RenderType.translucent();
    }

    static Block block(final ResourceLocation id) {
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    static boolean isSolidRender(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return state.isSolidRender();
    }
}
