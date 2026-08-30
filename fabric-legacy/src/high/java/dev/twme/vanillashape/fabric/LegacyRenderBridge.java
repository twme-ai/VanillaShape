package dev.twme.vanillashape.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.LevelRenderState;

/** Connects the 1.21.9+ vanilla submit phase to the version-specific renderer. */
public final class LegacyRenderBridge {
    private static ShapeRenderer renderer;

    private LegacyRenderBridge() {}

    static void initialize(final ShapeRenderer value) {
        renderer = value;
    }

    public static void submit(final PoseStack poseStack, final LevelRenderState state,
                              final SubmitNodeStorage storage) {
        final ShapeRenderer current = renderer;
        if (current != null) current.render(poseStack, state, storage);
    }
}
