package dev.twme.vanillashape.fabric.mixin;

import dev.twme.vanillashape.fabric.ClientInteractionHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void vanillashape$attack(final CallbackInfoReturnable<Boolean> callback) {
        if (ClientInteractionHandler.attack((Minecraft) (Object) this)) callback.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void vanillashape$use(final CallbackInfo callback) {
        if (ClientInteractionHandler.use((Minecraft) (Object) this)) callback.cancel();
    }

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void vanillashape$pick(final CallbackInfo callback) {
        if (ClientInteractionHandler.pick((Minecraft) (Object) this)) callback.cancel();
    }
}
