package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.internal.registry.InputParser;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.block.BaseBlock;
import dev.twme.vanillashape.common.ShapeType;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** Adds vanillashape:* entries to WorldEdit's normal block/pattern factory. */
final class VanillaShapeBlockParser extends InputParser<BaseBlock> {
    private final WorldEditProxyCodec codec;
    private final UnaryOperator<String> materialNormalizer;

    VanillaShapeBlockParser(final WorldEdit worldEdit, final WorldEditProxyCodec codec,
                            final UnaryOperator<String> materialNormalizer) {
        super(worldEdit);
        this.codec = codec;
        this.materialNormalizer = materialNormalizer;
    }

    @Override public BaseBlock parseFromInput(final String input, final ParserContext context)
            throws InputParseException {
        if (!isVanillaShape(input)) return null;
        requirePermission(context);
        try {
            final WorldEditBlockSpec spec = WorldEditBlockSpec.parse(input, materialNormalizer);
            return codec.encode(spec.template());
        } catch (final IllegalArgumentException error) {
            throw new InputParseException(TextComponent.of(error.getMessage()), error);
        }
    }

    @Override public Stream<String> getSuggestions(final String input, final ParserContext context) {
        if (!permitted(context)) return Stream.empty();
        final String normalized = input.startsWith("=") ? input.substring(1) : input;
        return Arrays.stream(ShapeType.values())
                .map(shape -> WorldEditBlockSpec.PREFIX + shape.name().toLowerCase(Locale.ROOT))
                .filter(value -> value.startsWith(normalized.toLowerCase(Locale.ROOT)));
    }

    private static boolean isVanillaShape(final String input) {
        final String normalized = input.startsWith("=") ? input.substring(1) : input;
        return normalized.toLowerCase(Locale.ROOT).startsWith(WorldEditBlockSpec.PREFIX);
    }

    static void requirePermission(final ParserContext context) throws InputParseException {
        if (!permitted(context)) {
            throw new InputParseException(TextComponent.of(
                    "You do not have permission to use VanillaShape WorldEdit blocks."));
        }
    }

    private static boolean permitted(final ParserContext context) {
        return context.getActor() == null || context.getActor().hasPermission("vanillashape.worldedit");
    }
}
