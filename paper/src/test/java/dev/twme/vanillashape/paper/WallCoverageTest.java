package dev.twme.vanillashape.paper;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallCoverageTest {
    @Test void acceptsFullOrUnionCoverageAndRejectsGaps() {
        assertTrue(BlockService.covers(7 / 16d, 0, 9 / 16d, 9 / 16d, 0,
                List.of(new BoundingBox(0, 0, 0, 1, 1, 1))));
        assertTrue(BlockService.covers(0, 0, 1, 1, 0, List.of(
                new BoundingBox(0, 0, 0, .5, .5, 1),
                new BoundingBox(.5, 0, 0, 1, .5, 1))));
        assertFalse(BlockService.covers(0, 0, 1, 1, 0, List.of(
                new BoundingBox(0, 0, 0, .45, .5, 1),
                new BoundingBox(.55, 0, 0, 1, .5, 1))));
    }
}
