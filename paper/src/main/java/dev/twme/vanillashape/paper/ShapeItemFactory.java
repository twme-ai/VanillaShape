package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Encodes a complete VanillaShape state into an otherwise vanilla block item. */
final class ShapeItemFactory {
    private static final int ITEM_VERSION = 2;
    private final NamespacedKey versionKey;
    private final NamespacedKey shapeKey;
    private final NamespacedKey materialKey;
    private final NamespacedKey modelKey;
    private final NamespacedKey facingKey;
    private final NamespacedKey cornerKey;
    private final NamespacedKey flagsKey;

    ShapeItemFactory(final VanillaShapePlugin plugin) {
        versionKey = new NamespacedKey(plugin, "item_version");
        shapeKey = new NamespacedKey(plugin, "shape");
        materialKey = new NamespacedKey(plugin, "material");
        modelKey = new NamespacedKey(plugin, "model");
        facingKey = new NamespacedKey(plugin, "facing");
        cornerKey = new NamespacedKey(plugin, "corner");
        flagsKey = new NamespacedKey(plugin, "flags");
    }

    ItemStack create(final SpecialBlock state, final int count) {
        final BlockData data = Bukkit.createBlockData(state.material());
        final BlockData display = state.shape() == ShapeType.MODEL && !state.model().isEmpty()
                ? Bukkit.createBlockData(state.model()) : data;
        final Material base = display.getMaterial().isItem() ? display.getMaterial()
                : data.getMaterial().isItem() ? data.getMaterial() : Material.PAPER;
        final ItemStack result = new ItemStack(base, Math.max(1, Math.min(count, base.getMaxStackSize())));
        final ItemMeta meta = result.getItemMeta();
        if (meta instanceof BlockDataMeta blockDataMeta) blockDataMeta.setBlockData(display);
        meta.displayName(Component.text(pretty(state.shape()), NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text(state.shape() == ShapeType.MODEL ? "model=" + state.model() : state.material(),
                        NamedTextColor.GRAY),
                Component.text("material=" + state.material(), NamedTextColor.GRAY),
                Component.text("facing=" + state.facing().name().toLowerCase(Locale.ROOT)
                        + ", corner=" + state.corner().name().toLowerCase(Locale.ROOT), NamedTextColor.DARK_GRAY),
                Component.text("VanillaShape block", NamedTextColor.DARK_AQUA)));
        final PersistentDataContainer dataContainer = meta.getPersistentDataContainer();
        dataContainer.set(versionKey, PersistentDataType.INTEGER, ITEM_VERSION);
        dataContainer.set(shapeKey, PersistentDataType.STRING, state.shape().name());
        dataContainer.set(materialKey, PersistentDataType.STRING, state.material());
        dataContainer.set(modelKey, PersistentDataType.STRING, state.model());
        dataContainer.set(facingKey, PersistentDataType.STRING, state.facing().name());
        dataContainer.set(cornerKey, PersistentDataType.STRING, state.corner().name());
        dataContainer.set(flagsKey, PersistentDataType.INTEGER, state.flags() & ~SpecialBlock.DOOR_UPPER);
        result.setItemMeta(meta);
        return result;
    }

    Optional<SpecialBlock> read(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        final PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        final Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        if (version == null || version < 1 || version > ITEM_VERSION) {
            return Optional.empty();
        }
        try {
            final ShapeType shape = ShapeType.valueOf(required(data, shapeKey));
            final String material = required(data, materialKey);
            Bukkit.createBlockData(material);
            final String model = version >= 2
                    ? java.util.Objects.requireNonNullElse(data.get(modelKey, PersistentDataType.STRING), "") : "";
            if (!model.isEmpty()) Bukkit.createBlockData(model);
            final Direction facing = Direction.valueOf(required(data, facingKey));
            final CornerShape corner = CornerShape.valueOf(required(data, cornerKey));
            final Integer flags = data.get(flagsKey, PersistentDataType.INTEGER);
            return Optional.of(new SpecialBlock("minecraft:overworld", 0, 0, 0,
                    shape, material, model, facing, corner, flags == null ? 0 : flags));
        } catch (final RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static String required(final PersistentDataContainer data, final NamespacedKey key) {
        final String value = data.get(key, PersistentDataType.STRING);
        if (value == null) throw new IllegalArgumentException("Missing VanillaShape item field");
        return value;
    }

    private static String pretty(final ShapeType shape) {
        final String[] words = shape.name().toLowerCase(Locale.ROOT).split("_");
        final StringBuilder result = new StringBuilder();
        for (final String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
