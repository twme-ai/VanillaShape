package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.WireProtocol;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

final class ClientBlockStore {
    private final Map<String, Map<BlockPos, SpecialBlock>> worlds = new HashMap<>();

    void accept(final byte[] bytes) throws IOException {
        final WireProtocol.Decoded decoded = WireProtocol.decode(bytes);
        switch (decoded.action()) {
            case WireProtocol.RESET -> worlds.put(decoded.world(), new HashMap<>());
            case WireProtocol.UPSERT -> map(decoded.world()).put(pos(decoded.block()), decoded.block());
            case WireProtocol.REMOVE -> map(decoded.world()).remove(
                    new BlockPos(decoded.x(), decoded.y(), decoded.z()));
            default -> { }
        }
    }

    Collection<SpecialBlock> blocks(final String world) {
        return new ArrayList<>(map(world).values());
    }

    void clear() { worlds.clear(); }

    private Map<BlockPos, SpecialBlock> map(final String world) {
        return worlds.computeIfAbsent(world, ignored -> new HashMap<>());
    }

    private static BlockPos pos(final SpecialBlock block) {
        return new BlockPos(block.x(), block.y(), block.z());
    }
}
