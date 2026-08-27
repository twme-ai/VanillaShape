package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.WireProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

final class ClientBlockStore {
    record Hit(SpecialBlock block, Vec3 location, Direction face, double distanceSquared) {}
    private final Map<String, Map<BlockPos, SpecialBlock>> worlds = new HashMap<>();
    private final TemplateModelResolver models = new TemplateModelResolver();

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

    SpecialBlock get(final String world, final BlockPos pos) {
        return map(world).get(pos);
    }

    Hit raycast(final String world, final Vec3 start, final Vec3 direction, final double range) {
        final Vec3 end = start.add(direction.normalize().scale(range));
        Hit nearest = null;
        for (final SpecialBlock block : map(world).values()) {
            if (!nearRayBounds(block, start, end)) continue;
            final java.util.List<ShapeGeometry.Box> hitBoxes;
            if (block.shape() == dev.twme.vanillashape.common.ShapeType.MODEL) {
                final TemplateModelResolver.Bounds bounds = models.bounds(block.model());
                hitBoxes = java.util.List.of(new ShapeGeometry.Box(bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            } else hitBoxes = ShapeGeometry.boxes(block);
            for (final ShapeGeometry.Box box : hitBoxes) {
                final AABB bounds = new AABB(block.x() + box.minX(), block.y() + box.minY(), block.z() + box.minZ(),
                        block.x() + box.maxX(), block.y() + box.maxY(), block.z() + box.maxZ());
                final var clipped = bounds.clip(start, end);
                if (clipped.isEmpty()) continue;
                final Vec3 location = clipped.get();
                final double distance = start.distanceToSqr(location);
                if (nearest == null || distance < nearest.distanceSquared()) {
                    nearest = new Hit(block, location, face(bounds, location, direction), distance);
                }
            }
        }
        return nearest;
    }

    void clear() { worlds.clear(); models.clear(); }

    private Map<BlockPos, SpecialBlock> map(final String world) {
        return worlds.computeIfAbsent(world, ignored -> new HashMap<>());
    }

    private static BlockPos pos(final SpecialBlock block) {
        return new BlockPos(block.x(), block.y(), block.z());
    }

    private static boolean nearRayBounds(final SpecialBlock block, final Vec3 start, final Vec3 end) {
        final double minX = Math.min(start.x, end.x) - 1;
        final double minY = Math.min(start.y, end.y) - 1;
        final double minZ = Math.min(start.z, end.z) - 1;
        final double maxX = Math.max(start.x, end.x) + 1;
        final double maxY = Math.max(start.y, end.y) + 1;
        final double maxZ = Math.max(start.z, end.z) + 1;
        return block.x() >= minX && block.x() <= maxX
                && block.y() >= minY && block.y() <= maxY
                && block.z() >= minZ && block.z() <= maxZ;
    }

    private static Direction face(final AABB box, final Vec3 point, final Vec3 direction) {
        final double epsilon = 1.0e-5;
        if (Math.abs(point.x - box.minX) < epsilon) return Direction.WEST;
        if (Math.abs(point.x - box.maxX) < epsilon) return Direction.EAST;
        if (Math.abs(point.y - box.minY) < epsilon) return Direction.DOWN;
        if (Math.abs(point.y - box.maxY) < epsilon) return Direction.UP;
        if (Math.abs(point.z - box.minZ) < epsilon) return Direction.NORTH;
        if (Math.abs(point.z - box.maxZ) < epsilon) return Direction.SOUTH;
        return Direction.getApproximateNearest(-direction.x, -direction.y, -direction.z);
    }
}
