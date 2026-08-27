package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.StateProperty;
import dev.twme.vanillashape.common.StateSchema;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DebugStickService {
    private final BlockService blocks;
    private final Map<UUID, String> selections = new HashMap<>();

    DebugStickService(final BlockService blocks) {
        this.blocks = blocks;
    }

    void select(final Player player, final SpecialBlock target, final boolean reverse) {
        final List<StateProperty> properties = StateSchema.properties(target.shape());
        if (properties.isEmpty()) throw new IllegalArgumentException(
                "This model stores its state in model BlockData; use /vshape replace model or WorldEdit.");
        final String selected = selections.get(player.getUniqueId());
        final int current = indexOf(properties, selected);
        final int next = current < 0 ? (reverse ? properties.size() - 1 : 0)
                : Math.floorMod(current + (reverse ? -1 : 1), properties.size());
        final StateProperty property = properties.get(next);
        selections.put(player.getUniqueId(), property.name());
        show(player, property, target);
    }

    void cycle(final Player player, final SpecialBlock target, final boolean reverse) {
        final List<StateProperty> properties = StateSchema.properties(target.shape());
        if (properties.isEmpty()) throw new IllegalArgumentException(
                "This model stores its state in model BlockData; use /vshape replace model or WorldEdit.");
        final String selected = selections.get(player.getUniqueId());
        final StateProperty property = properties.get(Math.max(0, indexOf(properties, selected)));
        selections.put(player.getUniqueId(), property.name());
        final SpecialBlock changed = StateSchema.cycle(target, property, reverse);
        blocks.putExact(changed);
        if (target.shape() == ShapeType.DOOR) updateDoorCounterpart(target, changed);
        show(player, property, changed);
    }

    private void updateDoorCounterpart(final SpecialBlock target, final SpecialBlock changed) {
        final int y = (target.flags() & SpecialBlock.DOOR_UPPER) != 0 ? target.y() - 1 : target.y() + 1;
        final SpecialBlock other = blocks.get(target.world(), target.x(), y, target.z());
        if (other == null || other.shape() != ShapeType.DOOR) return;
        final int half = other.flags() & SpecialBlock.DOOR_UPPER;
        blocks.putExact(new SpecialBlock(other.world(), other.x(), other.y(), other.z(),
                other.shape(), changed.material(), changed.facing(), changed.corner(),
                (changed.flags() & ~SpecialBlock.DOOR_UPPER) | half));
    }

    private static int indexOf(final List<StateProperty> properties, final String name) {
        if (name == null) return -1;
        for (int index = 0; index < properties.size(); index++) {
            if (properties.get(index).name().equals(name)) return index;
        }
        return -1;
    }

    private static void show(final Player player, final StateProperty property, final SpecialBlock block) {
        player.sendActionBar(Component.text(property.name() + " = ", NamedTextColor.GRAY)
                .append(Component.text(StateSchema.value(block, property.name()), NamedTextColor.AQUA)));
    }
}
