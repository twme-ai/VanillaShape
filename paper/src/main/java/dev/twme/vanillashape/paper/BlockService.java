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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

final class BlockService {
    private static final Direction[] DIRECTIONS = Direction.values();
    private final VanillaShapePlugin plugin;
    private final BlockRepository repository;
    private final Map<String, Map<BlockPosKey, SpecialBlock>> worlds = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();

    BlockService(final VanillaShapePlugin plugin, final BlockRepository repository) throws SQLException {
        this.plugin = plugin;
        this.repository = repository;
        for (final SpecialBlock block : repository.loadAll()) {
            map(block.world()).put(key(block), block);
        }
        // Upgrade pre-v5 wall records (which had no up/tall bits) immediately on startup.
        final java.util.List<SpecialBlock> loaded = worlds.values().stream()
                .flatMap(value -> value.values().stream()).toList();
        for (int pass = 0; pass < 2; pass++) for (final SpecialBlock block : loaded) {
            if (block.shape() == ShapeType.WALL) recompute(block.world(), key(block));
        }
    }

    static String worldKey(final World world) { return world.getKey().toString(); }

    Collection<SpecialBlock> inWorld(final String world) {
        return ListCopy.of(map(world).values());
    }

    SpecialBlock get(final String world, final int x, final int y, final int z) {
        return map(world).get(new BlockPosKey(x, y, z));
    }

    void neighborChanged(final World world, final int x, final int y, final int z) {
        recomputeAround(worldKey(world), x, y, z);
    }

    void put(final SpecialBlock block) {
        persistAndBroadcast(block);
        recomputeAround(block.world(), block.x(), block.y(), block.z());
    }

    /** Persists an explicitly edited state without immediately overwriting manual debug-stick values. */
    void putExact(final SpecialBlock block) {
        persistAndBroadcast(block);
    }

    SpecialBlock remove(final String world, final int x, final int y, final int z) {
        final BlockPosKey pos = new BlockPosKey(x, y, z);
        final SpecialBlock removed;
        synchronized (mutationLock) {
            removed = map(world).remove(pos);
            if (removed == null) return null;
            try {
                repository.remove(world, pos);
            } catch (final SQLException error) {
                map(world).put(pos, removed);
                throw new IllegalStateException("Could not remove block", error);
            }
        }
        broadcast(world, WireProtocol.remove(world, x, y, z));
        recomputeAround(world, x, y, z);
        return removed;
    }

    void applyExactBatch(final String world,
                         final Map<BlockPosKey, SpecialBlock> requestedUpserts,
                         final Set<BlockPosKey> requestedRemovals) {
        if (requestedUpserts.isEmpty() && requestedRemovals.isEmpty()) return;
        final Map<BlockPosKey, SpecialBlock> upserts = new HashMap<>();
        for (final var entry : requestedUpserts.entrySet()) {
            final BlockPosKey pos = entry.getKey();
            final SpecialBlock block = entry.getValue();
            if (!world.equals(block.world()) || block.x() != pos.x()
                    || block.y() != pos.y() || block.z() != pos.z()) {
                throw new IllegalArgumentException("Batch block coordinates do not match their key");
            }
            upserts.put(pos, block);
        }
        final Set<BlockPosKey> removals = new LinkedHashSet<>(requestedRemovals);
        removals.removeAll(upserts.keySet());
        synchronized (mutationLock) {
            try {
                repository.applyBatch(world, upserts, removals);
            } catch (final SQLException error) {
                throw new IllegalStateException("Could not store WorldEdit block batch", error);
            }
            final Map<BlockPosKey, SpecialBlock> worldBlocks = map(world);
            removals.forEach(worldBlocks::remove);
            worldBlocks.putAll(upserts);
        }

        final Runnable sync = () -> {
            removals.forEach(pos -> broadcast(world,
                    WireProtocol.remove(world, pos.x(), pos.y(), pos.z())));
            upserts.values().forEach(block -> broadcast(world, WireProtocol.upsert(block)));
        };
        if (Bukkit.isPrimaryThread()) sync.run();
        else Bukkit.getScheduler().runTask(plugin, sync);
    }

    void removeStructure(final SpecialBlock block) {
        remove(block.world(), block.x(), block.y(), block.z());
        if (block.shape() != ShapeType.DOOR) return;
        final int otherY = (block.flags() & SpecialBlock.DOOR_UPPER) != 0 ? block.y() - 1 : block.y() + 1;
        final SpecialBlock other = get(block.world(), block.x(), otherY, block.z());
        if (other != null && other.shape() == ShapeType.DOOR) {
            remove(other.world(), other.x(), other.y(), other.z());
        }
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
        // A wall's side height/post depends on the collision footprint immediately above it.
        affected.add(new BlockPosKey(x, y - 1, z));
        affected.add(new BlockPosKey(x, y + 1, z));
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
        } else if (current.shape() == ShapeType.WALL) {
            updated = current.withFlags(wallFlags(world, pos, current));
        } else if (current.shape() == ShapeType.FENCE) {
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

    /** Mirrors the vanilla 26.2 WallBlock none/low/tall and central-post rules. */
    private int wallFlags(final String world, final BlockPosKey pos, final SpecialBlock wall) {
        int flags = wall.flags() & ~(SpecialBlock.NORTH | SpecialBlock.EAST | SpecialBlock.SOUTH
                | SpecialBlock.WEST | SpecialBlock.WALL_UP | SpecialBlock.WALL_TALL_NORTH
                | SpecialBlock.WALL_TALL_EAST | SpecialBlock.WALL_TALL_SOUTH
                | SpecialBlock.WALL_TALL_WEST);
        for (final Direction direction : DIRECTIONS) {
            if (!connects(world, pos, ShapeType.WALL, direction)) continue;
            flags |= connectionFlag(direction);
            if (coveredAbove(world, pos, direction)) flags |= tallFlag(direction);
        }
        if (shouldRaiseWallPost(world, pos, flags)) flags |= SpecialBlock.WALL_UP;
        return flags;
    }

    private boolean shouldRaiseWallPost(final String world, final BlockPosKey pos, final int flags) {
        final SpecialBlock above = get(world, pos.x(), pos.y() + 1, pos.z());
        if (above != null && above.shape() == ShapeType.WALL
                && (above.flags() & SpecialBlock.WALL_UP) != 0) return true;
        final boolean northNone = (flags & SpecialBlock.NORTH) == 0;
        final boolean eastNone = (flags & SpecialBlock.EAST) == 0;
        final boolean southNone = (flags & SpecialBlock.SOUTH) == 0;
        final boolean westNone = (flags & SpecialBlock.WEST) == 0;
        if (northNone && eastNone && southNone && westNone) return true;
        if (northNone != southNone || westNone != eastNone) return true;
        final boolean northSouthTall = (flags & SpecialBlock.WALL_TALL_NORTH) != 0
                && (flags & SpecialBlock.WALL_TALL_SOUTH) != 0;
        final boolean eastWestTall = (flags & SpecialBlock.WALL_TALL_EAST) != 0
                && (flags & SpecialBlock.WALL_TALL_WEST) != 0;
        if (northSouthTall || eastWestTall) return false;
        if (above != null && above.shape() == ShapeType.MODEL
                && wallPostOverride(Bukkit.createBlockData(above.model()).getMaterial())) return true;
        final NamespacedKey key = NamespacedKey.fromString(world);
        final World bukkitWorld = key == null ? null : Bukkit.getWorld(key);
        if (bukkitWorld != null && wallPostOverride(
                bukkitWorld.getBlockAt(pos.x(), pos.y() + 1, pos.z()).getType())) return true;
        return coveredAbove(world, pos, null);
    }

    private static boolean wallPostOverride(final org.bukkit.Material material) {
        final String name = material.name();
        return material == org.bukkit.Material.TORCH || material == org.bukkit.Material.SOUL_TORCH
                || material == org.bukkit.Material.REDSTONE_TORCH
                || material == org.bukkit.Material.TRIPWIRE
                || name.equals("COPPER_TORCH") || name.equals("CACTUS_FLOWER")
                || name.endsWith("_SIGN") || name.endsWith("_BANNER")
                || name.endsWith("_PRESSURE_PLATE");
    }

    private boolean coveredAbove(final String worldKey, final BlockPosKey pos, final Direction direction) {
        final SpecialBlock custom = get(worldKey, pos.x(), pos.y() + 1, pos.z());
        final NamespacedKey key = NamespacedKey.fromString(worldKey);
        final World world = key == null ? null : Bukkit.getWorld(key);
        if (custom != null) {
            if (custom.shape() != ShapeType.MODEL || world == null) return customCoversBottom(custom, direction);
            final double[] area = testArea(direction);
            final org.bukkit.block.data.BlockData model = Bukkit.createBlockData(custom.model());
            return covers(area[0], area[1], area[2], area[3], 0,
                    model.getCollisionShape(new org.bukkit.Location(world, pos.x(), pos.y() + 1, pos.z()))
                            .getBoundingBoxes());
        }
        if (world == null) return false;
        final org.bukkit.block.Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (above.getType().isAir()) return false;
        final double[] area = testArea(direction);
        // Bukkit exposes Block#getCollisionShape in block-local 0..1 coordinates.
        return covers(area[0], area[1], area[2], area[3], 0,
                above.getCollisionShape().getBoundingBoxes());
    }

    private static boolean customCoversBottom(final SpecialBlock above, final Direction direction) {
        if (above.shape() == ShapeType.WALL) {
            if (direction == null) return (above.flags() & SpecialBlock.WALL_UP) != 0;
            return (above.flags() & connectionFlag(direction)) != 0;
        }
        if (above.shape() == ShapeType.SLAB) return (above.flags() & SpecialBlock.TOP) == 0;
        if (above.shape() == ShapeType.TRAPDOOR) {
            return (above.flags() & SpecialBlock.OPEN) == 0 && (above.flags() & SpecialBlock.TOP) == 0;
        }
        // Full-height fixed shapes touch enough of the bottom face for the wall test footprint.
        return above.shape() == ShapeType.FENCE_GATE || above.shape() == ShapeType.DOOR;
    }

    private static double[] testArea(final Direction direction) {
        final double a = 7 / 16d, b = 9 / 16d;
        if (direction == null) return new double[] {a, a, b, b};
        return switch (direction) {
            case NORTH -> new double[] {a, 0, b, b};
            case EAST -> new double[] {a, a, 1, b};
            case SOUTH -> new double[] {a, a, b, 1};
            case WEST -> new double[] {0, a, b, b};
        };
    }

    /** Checks rectangular coverage by the union of boxes touching the bottom plane. */
    static boolean covers(final double minX, final double minZ, final double maxX, final double maxZ,
                          final double bottomY, final Collection<BoundingBox> boxes) {
        final java.util.TreeSet<Double> xs = new java.util.TreeSet<>(Set.of(minX, maxX));
        final java.util.TreeSet<Double> zs = new java.util.TreeSet<>(Set.of(minZ, maxZ));
        for (final BoundingBox box : boxes) {
            if (box.getMinY() > bottomY + 1.0e-7 || box.getMaxY() <= bottomY) continue;
            if (box.getMaxX() <= minX || box.getMinX() >= maxX
                    || box.getMaxZ() <= minZ || box.getMinZ() >= maxZ) continue;
            xs.add(Math.max(minX, box.getMinX())); xs.add(Math.min(maxX, box.getMaxX()));
            zs.add(Math.max(minZ, box.getMinZ())); zs.add(Math.min(maxZ, box.getMaxZ()));
        }
        final Double[] x = xs.toArray(Double[]::new), z = zs.toArray(Double[]::new);
        for (int ix = 0; ix + 1 < x.length; ix++) for (int iz = 0; iz + 1 < z.length; iz++) {
            final double cx = (x[ix] + x[ix + 1]) / 2, cz = (z[iz] + z[iz + 1]) / 2;
            boolean found = false;
            for (final BoundingBox box : boxes) {
                if (box.getMinY() <= bottomY + 1.0e-7 && box.getMaxY() > bottomY
                        && cx >= box.getMinX() && cx <= box.getMaxX()
                        && cz >= box.getMinZ() && cz <= box.getMaxZ()) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean connects(final String worldKey, final BlockPosKey pos,
                             final ShapeType shape, final Direction direction) {
        final SpecialBlock custom = get(worldKey, pos.x() + direction.dx(), pos.y(), pos.z() + direction.dz());
        if (custom != null) {
            if (custom.shape() == ShapeType.FENCE_GATE) return custom.facing().perpendicular(direction);
            return custom.shape() == shape || custom.shape() == ShapeType.VERTICAL_SLAB;
        }
        final NamespacedKey key = NamespacedKey.fromString(worldKey);
        final World world = key == null ? null : Bukkit.getWorld(key);
        if (world == null) return false;
        final org.bukkit.block.Block neighbor = world.getBlockAt(
                pos.x() + direction.dx(), pos.y(), pos.z() + direction.dz());
        final org.bukkit.block.data.BlockData data = neighbor.getBlockData();
        if (data instanceof org.bukkit.block.data.type.Wall) return shape == ShapeType.WALL;
        if (data instanceof org.bukkit.block.data.type.Gate gate) {
            final boolean gateAlongX = Math.abs(gate.getFacing().getModX()) == 1;
            final boolean connectionAlongX = direction.dx() != 0;
            return gateAlongX != connectionAlongX;
        }
        if (shape == ShapeType.WALL && neighbor.getType() == org.bukkit.Material.IRON_BARS) return true;
        if (shape == ShapeType.FENCE && neighbor.getType().name().endsWith("_FENCE")) return true;
        return !connectionException(neighbor.getType()) && data.isFaceSturdy(
                switch (direction.opposite()) {
                    case NORTH -> org.bukkit.block.BlockFace.NORTH;
                    case EAST -> org.bukkit.block.BlockFace.EAST;
                    case SOUTH -> org.bukkit.block.BlockFace.SOUTH;
                    case WEST -> org.bukkit.block.BlockFace.WEST;
                }, org.bukkit.block.BlockSupport.FULL);
    }

    private static boolean connectionException(final org.bukkit.Material material) {
        final String name = material.name();
        return name.endsWith("_LEAVES") || name.endsWith("_SHULKER_BOX")
                || material == org.bukkit.Material.BARRIER || material == org.bukkit.Material.CARVED_PUMPKIN
                || material == org.bukkit.Material.JACK_O_LANTERN || material == org.bukkit.Material.MELON
                || material == org.bukkit.Material.PUMPKIN;
    }

    private void persistAndBroadcast(final SpecialBlock block) {
        final BlockPosKey pos = key(block);
        synchronized (mutationLock) {
            final SpecialBlock old = map(block.world()).put(pos, block);
            try {
                repository.upsert(block);
            } catch (final SQLException error) {
                if (old == null) map(block.world()).remove(pos); else map(block.world()).put(pos, old);
                throw new IllegalStateException("Could not store block", error);
            }
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
        return worlds.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>());
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

    private static int tallFlag(final Direction direction) {
        return switch (direction) {
            case NORTH -> SpecialBlock.WALL_TALL_NORTH;
            case EAST -> SpecialBlock.WALL_TALL_EAST;
            case SOUTH -> SpecialBlock.WALL_TALL_SOUTH;
            case WEST -> SpecialBlock.WALL_TALL_WEST;
        };
    }

    private static final class ListCopy {
        static <T> Collection<T> of(final Collection<T> source) {
            return new ArrayList<>(source);
        }
    }
}
