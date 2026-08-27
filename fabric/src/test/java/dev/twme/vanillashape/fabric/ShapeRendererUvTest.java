package dev.twme.vanillashape.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeRendererUvTest {
    @Test void localUvStaysInsideItsSingleAtlasSprite() {
        final float spriteMin = 0.375f;
        final float spriteMax = 0.390625f;

        final float start = ShapeRenderer.atlasCoordinate(spriteMin, spriteMax, 0);
        final float middle = ShapeRenderer.atlasCoordinate(spriteMin, spriteMax, .5f);
        final float end = ShapeRenderer.atlasCoordinate(spriteMin, spriteMax, 1);

        assertEquals(spriteMin, start);
        assertEquals((spriteMin + spriteMax) / 2, middle);
        assertEquals(spriteMax, end);
        assertTrue(middle >= spriteMin && middle <= spriteMax);
    }

    @Test void usesNeutralEntityOverlayInsteadOfRedDamageRow() {
        assertEquals(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                ShapeRenderer.noOverlay());
        assertNotEquals(0, ShapeRenderer.noOverlay());
    }
}
