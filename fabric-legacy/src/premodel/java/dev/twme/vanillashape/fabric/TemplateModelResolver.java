package dev.twme.vanillashape.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts baked geometry through the 1.21–1.21.4 BakedModel API. */
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
        final BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        final Map<String, Quad> unique = new LinkedHashMap<>();
        for (final Direction cull : Direction.values()) {
            for (final BakedQuad quad : model.getQuads(state, cull, RandomSource.create(42))) {
                add(unique, quad, cull);
            }
        }
        for (final BakedQuad quad : model.getQuads(state, null, RandomSource.create(42))) {
            add(unique, quad, null);
        }
        return List.copyOf(unique.values());
    }

    private static void add(final Map<String, Quad> unique, final BakedQuad baked,
                            final Direction cullDirection) {
        final var sprite = baked.getSprite();
        final float du = sprite.getU1() - sprite.getU0();
        final float dv = sprite.getV1() - sprite.getV0();
        final int[] packed = baked.getVertices();
        final int stride = packed.length / 4;
        final List<Vertex> vertices = new ArrayList<>(4);
        final StringBuilder key = new StringBuilder(baked.getDirection().name()).append('/').append(cullDirection);
        for (int index = 0; index < 4; index++) {
            final int base = index * stride;
            final float x = Float.intBitsToFloat(packed[base]);
            final float y = Float.intBitsToFloat(packed[base + 1]);
            final float z = Float.intBitsToFloat(packed[base + 2]);
            final float atlasU = Float.intBitsToFloat(packed[base + 4]);
            final float atlasV = Float.intBitsToFloat(packed[base + 5]);
            final float u = du == 0 ? 0 : (atlasU - sprite.getU0()) / du;
            final float v = dv == 0 ? 0 : (atlasV - sprite.getV0()) / dv;
            final Vertex vertex = new Vertex(x, y, z, u, v);
            vertices.add(vertex);
            key.append('/').append(Float.floatToIntBits(x)).append(',')
                    .append(Float.floatToIntBits(y)).append(',')
                    .append(Float.floatToIntBits(z)).append(',')
                    .append(Float.floatToIntBits(u)).append(',')
                    .append(Float.floatToIntBits(v));
        }
        unique.putIfAbsent(key.toString(), new Quad(List.copyOf(vertices), baked.getDirection(),
                cullDirection, baked.isShade()));
    }

    private static BlockState parseState(final String text) {
        final int bracket = text.indexOf('[');
        final String idText = bracket < 0 ? text : text.substring(0, bracket);
        final ResourceLocation id = ResourceLocation.tryParse(idText);
        final Block block = id == null ? null : MinecraftCompat.block(id);
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
