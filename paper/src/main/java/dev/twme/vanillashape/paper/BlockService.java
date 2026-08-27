package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.ConnectionResolver;
import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.WireProtocol;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

final class BlockService {
    private static final Direction[] DIRECTIONS = Direction.values();
    private final VanillaShapePlugin plugin;
    private final BlockRepository repository;
    private final Map<String, Map<BlockPosKey, SpecialBlock>> worlds = new HashMap<>();

    BlockService(final VanillaShapePlugin plugin, final BlockRepository repository) throws SQLException {
        this.plugin = plugin;
        this.repository = repository;
        for (final SpecialBlock block : repository.loadAll()) {
            map(block.world()).put(key(block), block);
        }
    }

    static String worldKey(final World world) { return world.getKey().toString(); }

    Collection<SpecialBlock> inWorld(final String world) {
        return ListCopy.of(map(world).values());
    }

    SpecialBlock get(final String world, final int x, final int y, final int z) {
        return map(world).get(new BlockPosKey(x, y, z));
    }

    void put(final SpecialBlock block) {
        persistAndBroadcast(block);
        recomputeAround(block.world(), block.x(), block.y(), block.z());
    }

    SpecialBlock remove(final String world, final int x, final int y, final int z) {
        final BlockPosKey pos = new BlockPosKey(x, y, z);
        final SpecialBlock removed = map(world).remove(pos);
        if (removed == null) return null;
        try {
            repository.remove(world, pos);
        } catch (final SQLException error) {
            map(world).put(pos, removed);
            throw new IllegalStateException("Could not remove block", error);
        }
        broadcast(world, WireProtocol.remove(world, x, y, z));
        recomputeAround(world, x, y, z);
        return removed;
    }

    void sync(final Player player) {
        final String world = worldKey(player.getWorld());
        player.sendPluginMessage(plugin, WireProtocol.CHANNEL, WireProtocol.reset(world));
        for (final SpecialBlock block : inWorld(world)) {
            player.sendPluginMessage(plugin, WireProtocol.CHANNEL, WireProtocol.upsert(block));
        }
    }

    SpecialBlock raycast(final Player player, final double distance) {
        final Vector origin = player.getEyeLocation().toVector();
        final Vector direction = player.getEyeLocation().getDirection().normalize();
        final String world = worldKey(player.getWorld());
        BlockPosKey previous = null;
        for (double d = 0; d <= distance; d += 0.05) {
            final Vector point = origin.clone().add(direction.clone().multiply(d));
            final BlockPosKey pos = new BlockPosKey(floor(point.getX()), floor(point.getY()), floor(point.getZ()));
            if (pos.equals(previous)) continue;
            previous = pos;
            final SpecialBlock hit = map(world).get(pos);
            if (hit != null) return hit;
        }
        return null;
    }

    private void recomputeAround(final String world, final int x, final int y, final int z) {
        final Set<BlockPosKey> affected = new LinkedHashSet<>();
        affected.add(new BlockPosKey(x, y, z));
        for (final Direction direction : DIRECTIONS) {
            affected.add(new BlockPosKey(x + direction.dx(), y, z + direction.dz()));
        }
        for (final BlockPosKey pos : affected) recompute(world, pos);
    }

    private void recompute(final String world, final BlockPosKey pos) {
        final SpecialBlock current = map(world).get(pos);
        if (current == null) return;
        SpecialBlock updated = current;
        if (current.shape() == ShapeType.STAIRS || current.shape() == ShapeType.VERTICAL_SLAB) {
            final CornerShape corner = ConnectionResolver.corner(current,
                    (dx, dz) -> get(world, pos.x() + dx, pos.y(), pos.z() + dz));
            updated = current.withCorner(corner);
        } else if (current.shape() == ShapeType.WALL || current.shape() == ShapeType.FENCE) {
            int flags = current.flags() & ~(SpecialBlock.NORTH | SpecialBlock.EAST
                    | SpecialBlock.SOUTH | SpecialBlock.WEST);
            for (final Direction direction : DIRECTIONS) {
                if (connects(world, pos, current.shape(), direction)) {
                    flags |= connectionFlag(direction);
                }
            }
            updated = current.withFlags(flags);
        }
        if (!updated.equals(current)) persistAndBroadcast(updated);
    }

    private boolean connects(final String worldKey, final BlockPosKey pos,
                             final ShapeType shape, final Direction direction) {
        final SpecialBlock custom = get(worldKey, pos.x() + direction.dx(), pos.y(), pos.z() + direction.dz());
        if (custom != null) {
            return custom.shape() == shape || custom.shape() == ShapeType.VERTICAL_SLAB
                    || custom.shape() == ShapeType.FENCE_GATE;
        }
        final NamespacedKey key = NamespacedKey.fromString(worldKey);
        final World world = key == null ? null : Bukkit.getWorld(key);
        return world != null && world.getBlockAt(pos.x() + direction.dx(), pos.y(), pos.z() + direction.dz())
                .getType().isOccluding();
    }

    private void persistAndBroadcast(final SpecialBlock block) {
        final BlockPosKey pos = key(block);
        final SpecialBlock old = map(block.world()).put(pos, block);
        try {
            repository.upsert(block);
        } catch (final SQLException error) {
            if (old == null) map(block.world()).remove(pos); else map(block.world()).put(pos, old);
            throw new IllegalStateException("Could not store block", error);
        }
        broadcast(block.world(), WireProtocol.upsert(block));
    }

    private void broadcast(final String world, final byte[] payload) {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (!worldKey(player.getWorld()).equals(world)) continue;
            try {
                player.sendPluginMessage(plugin, WireProtocol.CHANNEL, payload);
            } catch (final RuntimeException error) {
                plugin.getLogger().log(Level.FINE, "Client is not listening on VanillaShape channel", error);
            }
        }
    }

    private Map<BlockPosKey, SpecialBlock> map(final String world) {
        return worlds.computeIfAbsent(world, ignored -> new HashMap<>());
    }

    private static BlockPosKey key(final SpecialBlock block) {
        return new BlockPosKey(block.x(), block.y(), block.z());
    }

    private static int floor(final double value) { return (int) Math.floor(value); }

    private static int connectionFlag(final Direction direction) {
        return switch (direction) {
            case NORTH -> SpecialBlock.NORTH;
            case EAST -> SpecialBlock.EAST;
            case SOUTH -> SpecialBlock.SOUTH;
            case WEST -> SpecialBlock.WEST;
        };
    }

    private static final class ListCopy {
        static <T> Collection<T> of(final Collection<T> source) {
            return new ArrayList<>(source);
        }
    }
}
