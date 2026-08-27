package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockRepositoryBatchTest {
    @TempDir Path temporary;

    @Test void commitsUpsertsAndRemovalsInOneTransaction() throws Exception {
        final String world = "minecraft:overworld";
        final BlockPosKey first = new BlockPosKey(1, 2, 3);
        final BlockPosKey second = new BlockPosKey(-4, 70, 8);
        try (var repository = new BlockRepository(temporary.resolve("blocks.db"))) {
            repository.upsert(block(world, first, ShapeType.FENCE));
            repository.applyBatch(world,
                    Map.of(second, block(world, second, ShapeType.VERTICAL_SLAB)), Set.of(first));
            final var loaded = repository.loadAll();
            assertEquals(1, loaded.size());
            assertEquals(ShapeType.VERTICAL_SLAB, loaded.getFirst().shape());
            assertEquals(second.x(), loaded.getFirst().x());
        }
    }

    private static SpecialBlock block(final String world, final BlockPosKey pos, final ShapeType shape) {
        return new SpecialBlock(world, pos.x(), pos.y(), pos.z(), shape,
                "minecraft:stone", Direction.NORTH, CornerShape.STRAIGHT, 0);
    }
}
