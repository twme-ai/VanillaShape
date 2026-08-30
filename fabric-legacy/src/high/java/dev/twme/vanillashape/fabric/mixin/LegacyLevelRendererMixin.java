package dev.twme.vanillashape.fabric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.twme.vanillashape.fabric.LegacyRenderBridge;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LegacyLevelRendererMixin {
    @Inject(method = "submitBlockEntities", at = @At("TAIL"))
    private void vanillashape$submitBlocks(final PoseStack poseStack, final LevelRenderState state,
                                           final SubmitNodeStorage storage, final CallbackInfo callback) {
        LegacyRenderBridge.submit(poseStack, state, storage);
    }
}
