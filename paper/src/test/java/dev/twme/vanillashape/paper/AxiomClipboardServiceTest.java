package dev.twme.vanillashape.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AxiomClipboardServiceTest {
    @Test void normalizesBoundsAndMeasuresVolumeWithoutOverflow() {
        final var bounds = AxiomClipboardService.Bounds.of(5, 10, 8, 3, 12, 4);
        assertEquals(3, bounds.minX());
        assertEquals(3, bounds.sizeX());
        assertEquals(3, bounds.sizeY());
        assertEquals(5, bounds.sizeZ());
        assertEquals(45, bounds.volume());

        final var enormous = AxiomClipboardService.Bounds.of(
                Integer.MIN_VALUE, -64, Integer.MIN_VALUE,
                Integer.MAX_VALUE, 319, Integer.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, enormous.volume());
    }

    @Test void translationRejectsIntegerCoordinateOverflow() {
        assertEquals(110, AxiomClipboardService.translated(100, 10));
        assertThrows(IllegalArgumentException.class,
                () -> AxiomClipboardService.translated(Integer.MAX_VALUE, 1));
    }
}
