package dev.twme.vanillashape.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts baked-model geometry on the remapped 1.21.11 client. */
final class TemplateModelResolver {
    record Vertex(float x, float y, float z, float u, float v) {}
    record Quad(List<Vertex> vertices, Direction direction, Direction cullDirection, boolean shade) {}
    record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}

    private final Map<String, List<Quad>> cache = new HashMap<>();

    List<Quad> resolve(final String blockData) {
        return cache.computeIfAbsent(blockData, this::parse);
    }

    void clear() {
        cache.clear();
    }

    boolean occludesFullCube(final String blockData) {
        final BlockState state = parseState(blockData);
        return state != null && state.isSolidRender();
    }

    Bounds bounds(final String blockData) {
        float minX = 1, minY = 1, minZ = 1, maxX = 0, maxY = 0, maxZ = 0;
        for (final Quad quad : resolve(blockData)) for (final Vertex vertex : quad.vertices()) {
            minX = Math.min(minX, vertex.x());
            minY = Math.min(minY, vertex.y());
            minZ = Math.min(minZ, vertex.z());
            maxX = Math.max(maxX, vertex.x());
            maxY = Math.max(maxY, vertex.y());
            maxZ = Math.max(maxZ, vertex.z());
        }
        if (maxX < minX) return new Bounds(0, 0, 0, 1, 1, 1);
        final float epsilon = .015625f;
        if (maxX - minX < epsilon) { minX -= epsilon / 2; maxX += epsilon / 2; }
        if (maxY - minY < epsilon) { minY -= epsilon / 2; maxY += epsilon / 2; }
        if (maxZ - minZ < epsilon) { minZ -= epsilon / 2; maxZ += epsilon / 2; }
        return new Bounds(Math.max(0, minX), Math.max(0, minY), Math.max(0, minZ),
                Math.min(1, maxX), Math.min(1, maxY), Math.min(1, maxZ));
    }

    private List<Quad> parse(final String text) {
        final BlockState state = parseState(text);
        if (state == null) return List.of();
        final var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        final List<BlockModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42), parts);
        final Map<String, Quad> unique = new LinkedHashMap<>();
        for (final BlockModelPart part : parts) {
            for (final Direction cull : Direction.values()) {
                for (final BakedQuad quad : part.getQuads(cull)) add(unique, quad, cull);
            }
            for (final BakedQuad quad : part.getQuads(null)) add(unique, quad, null);
        }
        return List.copyOf(unique.values());
    }

    private static void add(final Map<String, Quad> unique, final BakedQuad baked,
                            final Direction cullDirection) {
        final var sprite = baked.sprite();
        final float du = sprite.getU1() - sprite.getU0();
        final float dv = sprite.getV1() - sprite.getV0();
        final List<Vertex> vertices = new ArrayList<>(4);
        final StringBuilder key = new StringBuilder(baked.direction().name()).append('/').append(cullDirection);
        for (int index = 0; index < 4; index++) {
            final Vector3fc position = baked.position(index);
            final long packed = baked.packedUV(index);
            final float u = du == 0 ? 0 : (UVPair.unpackU(packed) - sprite.getU0()) / du;
            final float v = dv == 0 ? 0 : (UVPair.unpackV(packed) - sprite.getV0()) / dv;
            final Vertex vertex = new Vertex(position.x(), position.y(), position.z(), u, v);
            vertices.add(vertex);
            key.append('/').append(Float.floatToIntBits(vertex.x())).append(',')
                    .append(Float.floatToIntBits(vertex.y())).append(',')
                    .append(Float.floatToIntBits(vertex.z())).append(',')
                    .append(Float.floatToIntBits(vertex.u())).append(',')
                    .append(Float.floatToIntBits(vertex.v()));
        }
        unique.putIfAbsent(key.toString(), new Quad(List.copyOf(vertices), baked.direction(),
                cullDirection, baked.shade()));
    }

    private static BlockState parseState(final String text) {
        final int bracket = text.indexOf('[');
        final String idText = bracket < 0 ? text : text.substring(0, bracket);
        final Identifier id = Identifier.tryParse(idText);
        final Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
        if (block == null) return null;
        BlockState state = block.defaultBlockState();
        if (bracket >= 0 && text.endsWith("]")) {
            for (final String assignment : text.substring(bracket + 1, text.length() - 1).split(",")) {
                final int equals = assignment.indexOf('=');
                if (equals < 1) continue;
                final String name = assignment.substring(0, equals).trim();
                final String value = assignment.substring(equals + 1).trim();
                for (final Property<?> property : state.getProperties()) {
                    if (property.getName().equals(name)) state = set(state, property, value);
                }
            }
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState set(final BlockState state, final Property property, final String value) {
        return (BlockState) property.getValue(value)
                .map(parsed -> state.setValue(property, (Comparable) parsed)).orElse(state);
    }
}
