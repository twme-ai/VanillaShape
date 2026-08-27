package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authoritative right-click behavior and per-player material replacement mode. */
final class InteractionService {
    private static final long SAME_TARGET_DEBOUNCE_NANOS = 150_000_000L;
    private final VanillaShapePlugin plugin;
    private final BlockService blocks;
    private final ShapeItemFactory items;
    private final Set<UUID> replacement = new HashSet<>();
    private final Map<UUID, RecentInteraction> recentInteractions = new HashMap<>();

    InteractionService(final VanillaShapePlugin plugin, final BlockService blocks,
                       final ShapeItemFactory items) {
        this.plugin = plugin;
        this.blocks = blocks;
        this.items = items;
    }

    boolean replacementMode(final Player player) { return replacement.contains(player.getUniqueId()); }

    boolean setReplacementMode(final Player player, final Boolean requested) {
        final boolean enabled = requested == null ? !replacementMode(player) : requested;
        if (enabled) replacement.add(player.getUniqueId()); else replacement.remove(player.getUniqueId());
        return enabled;
    }

    void interact(final Player player, final SpecialBlock target) {
        if (isDuplicateInteraction(player, target)) return;
        if (replacementMode(player)) {
            final String material = heldMaterial(player).getAsString();
            updateStructure(target, block -> block.withMaterial(material));
            player.sendActionBar(Component.text("Material → " + material, NamedTextColor.AQUA));
            return;
        }
        switch (target.shape()) {
            case DOOR, TRAPDOOR, FENCE_GATE -> {
                final boolean open = (target.flags() & SpecialBlock.OPEN) == 0;
                updateStructure(target, block -> block.withFlags(open
                        ? block.flags() | SpecialBlock.OPEN : block.flags() & ~SpecialBlock.OPEN));
            }
            case MODEL -> interactModel(player, target);
            default -> throw new IllegalArgumentException("That VanillaShape block has no right-click action.");
        }
    }

    void forget(final Player player) {
        replacement.remove(player.getUniqueId());
        recentInteractions.remove(player.getUniqueId());
    }

    /** Fabric's intercepted use action can arrive twice for one physical click on some clients. */
    private boolean isDuplicateInteraction(final Player player, final SpecialBlock target) {
        final long now = System.nanoTime();
        final UUID playerId = player.getUniqueId();
        final RecentInteraction previous = recentInteractions.put(playerId,
                new RecentInteraction(target.world(), target.x(), target.y(), target.z(), now));
        return previous != null && previous.world().equals(target.world())
                && previous.x() == target.x() && previous.y() == target.y() && previous.z() == target.z()
                && now - previous.atNanos() < SAME_TARGET_DEBOUNCE_NANOS;
    }

    private void interactModel(final Player player, final SpecialBlock target) {
        final BlockData data = Bukkit.createBlockData(target.model());
        boolean changed = false;
        boolean momentary = false;
        if (data instanceof Openable openable) {
            openable.setOpen(!openable.isOpen());
            changed = true;
        } else if (data instanceof Powerable powerable) {
            powerable.setPowered(!powerable.isPowered());
            changed = true;
            momentary = data instanceof Switch
                    && data.getMaterial().name().endsWith("_BUTTON") && powerable.isPowered();
        } else if (data instanceof Lightable lightable) {
            lightable.setLit(!lightable.isLit());
            changed = true;
        }
        if (!changed) throw new IllegalArgumentException("That model has no supported right-click state.");
        final SpecialBlock updated = target.withModel(data.getAsString());
        blocks.putExact(updated);
        if (momentary) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                final SpecialBlock current = blocks.get(target.world(), target.x(), target.y(), target.z());
                if (current == null || current.shape() != ShapeType.MODEL) return;
                final BlockData currentData = Bukkit.createBlockData(current.model());
                if (currentData instanceof Powerable powerable && powerable.isPowered()) {
                    powerable.setPowered(false);
                    blocks.putExact(current.withModel(currentData.getAsString()));
                }
            }, 20L);
        }
    }

    private void updateStructure(final SpecialBlock target,
                                 final java.util.function.UnaryOperator<SpecialBlock> update) {
        blocks.putExact(update.apply(target));
        if (target.shape() != ShapeType.DOOR) return;
        final int otherY = (target.flags() & SpecialBlock.DOOR_UPPER) != 0 ? target.y() - 1 : target.y() + 1;
        final SpecialBlock other = blocks.get(target.world(), target.x(), otherY, target.z());
        if (other != null && other.shape() == ShapeType.DOOR) blocks.putExact(update.apply(other));
    }

    private BlockData heldMaterial(final Player player) {
        final ItemStack item = player.getInventory().getItemInMainHand();
        final var shape = items.read(item);
        if (shape.isPresent()) return Bukkit.createBlockData(shape.get().material());
        final Material type = item.getType();
        if (!type.isBlock() || type.isAir()) {
            throw new IllegalArgumentException("Hold a non-air block item as the replacement material.");
        }
        if (item.getItemMeta() instanceof BlockDataMeta meta && meta.hasBlockData()) {
            return meta.getBlockData(type);
        }
        return type.createBlockData();
    }

    private record RecentInteraction(String world, int x, int y, int z, long atNanos) {}
}
