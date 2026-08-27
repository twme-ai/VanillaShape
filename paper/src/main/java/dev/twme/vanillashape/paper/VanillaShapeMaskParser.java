package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.internal.registry.InputParser;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import dev.twme.vanillashape.common.ShapeType;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** Exact virtual-block mask used by //replace and mask-taking WorldEdit commands. */
class VanillaShapeMaskParser extends InputParser<Mask> {
    private final BlockService blocks;
    private final WorldEditProxyCodec codec;
    private final UnaryOperator<String> materialNormalizer;

    VanillaShapeMaskParser(final WorldEdit worldEdit, final BlockService blocks,
                           final WorldEditProxyCodec codec,
                           final UnaryOperator<String> materialNormalizer) {
        super(worldEdit);
        this.blocks = blocks;
        this.codec = codec;
        this.materialNormalizer = materialNormalizer;
    }

    @Override public Mask parseFromInput(final String input, final ParserContext context)
            throws InputParseException {
        if (!isVanillaShape(input)) return null;
        VanillaShapeBlockParser.requirePermission(context);
        final WorldEditBlockSpec spec;
        try {
            // Masks only compare explicitly supplied fields, so the fallback is never persisted
            // or matched. It merely satisfies the template's structural requirement.
            spec = WorldEditBlockSpec.parse(input, materialNormalizer, "minecraft:air");
        } catch (final IllegalArgumentException error) {
            throw new InputParseException(TextComponent.of(error.getMessage()), error);
        }
        final var extent = context.requireExtent();
        final String world = context.getWorld() == null ? null
                : BlockService.worldKey(BukkitAdapter.adapt(context.getWorld()));
        return position -> {
            var block = world == null ? null : blocks.get(world, position.x(), position.y(), position.z());
            if (block == null) {
                block = codec.decode(extent.getFullBlock(position),
                        world == null ? "minecraft:overworld" : world,
                        position.x(), position.y(), position.z()).orElse(null);
            }
            return block != null && spec.matches(block);
        };
    }

    @Override public Stream<String> getSuggestions(final String input, final ParserContext context) {
        final String normalized = input.startsWith("=") ? input.substring(1) : input;
        return Arrays.stream(ShapeType.values())
                .map(shape -> WorldEditBlockSpec.PREFIX + shape.name().toLowerCase(Locale.ROOT))
                .filter(value -> value.startsWith(normalized.toLowerCase(Locale.ROOT)));
    }

    private static boolean isVanillaShape(final String input) {
        final String normalized = input.startsWith("=") ? input.substring(1) : input;
        return normalized.toLowerCase(Locale.ROOT).startsWith(WorldEditBlockSpec.PREFIX);
    }
}
