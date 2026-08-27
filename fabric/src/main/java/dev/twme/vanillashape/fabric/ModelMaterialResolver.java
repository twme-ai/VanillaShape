package dev.twme.vanillashape.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ModelMaterialResolver {
    record Face(TextureAtlasSprite sprite, int color) {}
    record Resolved(Map<net.minecraft.core.Direction, List<Face>> faces, List<Face> fallback, boolean translucent) {
        List<Face> face(final net.minecraft.core.Direction direction) {
            return faces.getOrDefault(direction, fallback);
        }
    }

    private final Map<String, Parsed> cache = new HashMap<>();

    Resolved resolve(final String blockData, final ClientLevel level, final BlockPos pos) {
        final Parsed parsed = cache.computeIfAbsent(blockData, this::parse);
        final Map<net.minecraft.core.Direction, List<Face>> result = new EnumMap<>(net.minecraft.core.Direction.class);
        boolean translucent = parsed.fallback.sprite().transparency().hasTranslucent();
        for (final var entry : parsed.faces.entrySet()) {
            final List<Face> layers = new ArrayList<>();
            for (final Sample sample : entry.getValue()) {
                layers.add(new Face(sample.sprite, tint(sample.tintIndex, parsed.state, level, pos)));
                translucent |= sample.sprite.transparency().hasTranslucent();
            }
            result.put(entry.getKey(), List.copyOf(layers));
        }
        return new Resolved(result,
                List.of(new Face(parsed.fallback.sprite,
                        tint(parsed.fallback.tintIndex, parsed.state, level, pos))),
                translucent);
    }

    void clear() { cache.clear(); }

    private Parsed parse(final String text) {
        final int bracket = text.indexOf('[');
        final String idText = bracket < 0 ? text : text.substring(0, bracket);
        final Identifier id = Identifier.tryParse(idText);
        final Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
        if (block == null) return fallback();
        BlockState state = block.defaultBlockState();
        if (bracket >= 0 && text.endsWith("]")) {
            final String properties = text.substring(bracket + 1, text.length() - 1);
            for (final String assignment : properties.split(",")) {
                final int equals = assignment.indexOf('=');
                if (equals < 1) continue;
                final String name = assignment.substring(0, equals).trim();
                final String value = assignment.substring(equals + 1).trim();
                for (final Property<?> property : state.getProperties()) {
                    if (property.getName().equals(name)) state = set(state, property, value);
                }
            }
        }

        final var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        final List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42), parts);
        final Map<net.minecraft.core.Direction, List<Sample>> faces = new EnumMap<>(net.minecraft.core.Direction.class);
        for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            final List<Sample> samples = samples(parts, direction);
            if (!samples.isEmpty()) faces.put(direction, samples);
        }
        final var particle = model.particleMaterial().sprite();
        final Sample fallback = new Sample(particle, -1);
        return new Parsed(state, faces, fallback);
    }

    private static List<Sample> samples(
            final List<BlockStateModelPart> parts, final net.minecraft.core.Direction direction) {
        final Map<SampleKey, Sample> unique = new java.util.LinkedHashMap<>();
        for (final BlockStateModelPart part : parts) {
            for (final BakedQuad quad : part.getQuads(direction)) add(unique, quad);
        }
        for (final BlockStateModelPart part : parts) {
            for (final BakedQuad quad : part.getQuads(null)) {
                if (quad.direction() == direction) add(unique, quad);
            }
        }
        return List.copyOf(unique.values());
    }

    private static void add(final Map<SampleKey, Sample> unique, final BakedQuad quad) {
        final Sample sample = new Sample(quad.materialInfo().sprite(), quad.materialInfo().tintIndex());
        unique.putIfAbsent(new SampleKey(sample.sprite.contents().name(), sample.tintIndex), sample);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState set(final BlockState state, final Property property, final String value) {
        return (BlockState) property.getValue(value)
                .map(parsed -> state.setValue(property, (Comparable) parsed)).orElse(state);
    }

    private static int tint(final int index, final BlockState state,
                            final ClientLevel level, final BlockPos pos) {
        if (index < 0) return 0xFFFFFFFF;
        final BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, index);
        return source == null ? 0xFFFFFFFF : source.colorInWorld(state, level, pos);
    }

    private static Parsed fallback() {
        final BlockState state = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("stone"))
                .defaultBlockState();
        final var material = Minecraft.getInstance().getModelManager().getBlockStateModelSet()
                .get(state).particleMaterial().sprite();
        return new Parsed(state, Map.of(), new Sample(material, -1));
    }

    private record Sample(TextureAtlasSprite sprite, int tintIndex) {}
    private record SampleKey(Identifier sprite, int tintIndex) {}
    private record Parsed(BlockState state, Map<net.minecraft.core.Direction, List<Sample>> faces, Sample fallback) {}
}
