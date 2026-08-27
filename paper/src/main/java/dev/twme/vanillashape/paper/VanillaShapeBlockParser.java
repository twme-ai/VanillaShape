package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.internal.registry.InputParser;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.block.BaseBlock;
import dev.twme.vanillashape.common.ShapeType;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;

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
            WorldEditBlockSpec spec = WorldEditBlockSpec.parse(input, materialNormalizer, "minecraft:air");
            if (!spec.materialSpecified()) {
                spec = WorldEditBlockSpec.parse(input, materialNormalizer, heldMaterial(context));
            }
            if (spec.template().shape() == ShapeType.MODEL && !spec.modelSpecified()) {
                throw new IllegalArgumentException("vanillashape:model requires model:\"minecraft:block[state]...\"");
            }
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

    /** WorldEdit patterns without material NBT intentionally inherit the actor's held block. */
    private static String heldMaterial(final ParserContext context) throws InputParseException {
        if (context.getActor() == null) {
            throw new InputParseException(TextComponent.of(
                    "Specify material:\"minecraft:block[state]\" when no player is holding a block."));
        }
        final CommandSender sender;
        try {
            sender = BukkitAdapter.adapt(context.getActor());
        } catch (final RuntimeException unavailable) {
            throw new InputParseException(TextComponent.of(
                    "Specify material:\"minecraft:block[state]\" when no player is holding a block."), unavailable);
        }
        if (!(sender instanceof Player player)) {
            throw new InputParseException(TextComponent.of(
                    "Hold a non-air block item, or specify material:\"minecraft:block[state]\"."));
        }
        final ItemStack item = player.getInventory().getItemInMainHand();
        final Material type = item.getType();
        if (!type.isBlock() || type.isAir()) {
            throw new InputParseException(TextComponent.of(
                    "Hold a non-air block item, or specify material:\"minecraft:block[state]\"."));
        }
        if (item.getItemMeta() instanceof BlockDataMeta meta && meta.hasBlockData()) {
            return meta.getBlockData(type).getAsString();
        }
        return type.createBlockData().getAsString();
    }

    private static boolean permitted(final ParserContext context) {
        return context.getActor() == null || context.getActor().hasPermission("vanillashape.worldedit");
    }
}
