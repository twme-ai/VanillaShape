package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.SpecialBlock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Paper-authoritative clipboard used by the public Axiom CustomTool integration. */
final class AxiomClipboardService {
    static final long MAX_SELECTION_VOLUME = 16_777_216L;
    static final int MAX_SPECIAL_BLOCKS = 100_000;

    private final BlockService blocks;
    private final Map<UUID, ClipboardData> clipboards = new HashMap<>();

    AxiomClipboardService(final BlockService blocks) {
        this.blocks = blocks;
    }

    int copy(final Player player, final int x1, final int y1, final int z1,
             final int x2, final int y2, final int z2) {
        final Bounds bounds = Bounds.of(x1, y1, z1, x2, y2, z2);
        validateBounds(player.getWorld(), bounds);
        final String world = BlockService.worldKey(player.getWorld());
        final List<SpecialBlock> selected = blocks.inWorld(world).stream()
                .filter(block -> bounds.contains(block.x(), block.y(), block.z()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("The Axiom selection contains no VanillaShape blocks.");
        }
        if (selected.size() > MAX_SPECIAL_BLOCKS) {
            throw new IllegalArgumentException("That selection contains too many VanillaShape blocks.");
        }
        clipboards.put(player.getUniqueId(), new ClipboardData(bounds, selected));
        player.sendActionBar(Component.text("Copied " + selected.size()
                + " VanillaShape block(s). Choose a paste origin and press Enter.", NamedTextColor.GREEN));
        return selected.size();
    }

    int paste(final Player player, final int originX, final int originY, final int originZ) {
        final ClipboardData clipboard = clipboards.get(player.getUniqueId());
        if (clipboard == null) {
            throw new IllegalArgumentException("Copy a VanillaShape selection in Axiom first.");
        }
        final Bounds source = clipboard.bounds();
        final int maxX = translated(originX, source.sizeX() - 1L);
        final int maxY = translated(originY, source.sizeY() - 1L);
        final int maxZ = translated(originZ, source.sizeZ() - 1L);
        final Bounds destination = Bounds.of(originX, originY, originZ, maxX, maxY, maxZ);
        validateBounds(player.getWorld(), destination);

        final String world = BlockService.worldKey(player.getWorld());
        final Map<BlockPosKey, SpecialBlock> upserts = new HashMap<>();
        for (final SpecialBlock sourceBlock : clipboard.blocks()) {
            final int x = translated(originX, (long) sourceBlock.x() - source.minX());
            final int y = translated(originY, (long) sourceBlock.y() - source.minY());
            final int z = translated(originZ, (long) sourceBlock.z() - source.minZ());
            final var backing = player.getWorld().getBlockAt(x, y, z);
            if (!backing.getType().isAir()) {
                throw new IllegalArgumentException("A real block occupies the VanillaShape paste at "
                        + x + ", " + y + ", " + z + ".");
            }
            final SpecialBlock moved = sourceBlock.at(world, x, y, z);
            upserts.put(new BlockPosKey(x, y, z), moved);
        }

        // Clipboard air is meaningful: clear virtual blocks inside the destination box that do
        // not have a copied virtual block at the corresponding source coordinate.
        final Set<BlockPosKey> removals = new LinkedHashSet<>();
        for (final SpecialBlock current : blocks.inWorld(world)) {
            if (!destination.contains(current.x(), current.y(), current.z())) continue;
            final BlockPosKey position = new BlockPosKey(current.x(), current.y(), current.z());
            if (!upserts.containsKey(position)) removals.add(position);
        }
        blocks.applyExactBatch(world, upserts, removals);
        player.sendActionBar(Component.text("Pasted " + upserts.size()
                + " VanillaShape block(s).", NamedTextColor.GREEN));
        return upserts.size();
    }

    void forget(final Player player) {
        clipboards.remove(player.getUniqueId());
    }

    static int translated(final int origin, final long offset) {
        final long result = (long) origin + offset;
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The pasted selection is outside Minecraft coordinates.");
        }
        return (int) result;
    }

    private static void validateBounds(final World world, final Bounds bounds) {
        if (bounds.volume() > MAX_SELECTION_VOLUME) {
            throw new IllegalArgumentException("Axiom VanillaShape selections are limited to "
                    + MAX_SELECTION_VOLUME + " blocks of volume.");
        }
        if (bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight()) {
            throw new IllegalArgumentException("That selection is outside the world height.");
        }
        final var border = world.getWorldBorder();
        if (!border.isInside(world.getBlockAt(bounds.minX(), bounds.minY(), bounds.minZ()).getLocation())
                || !border.isInside(world.getBlockAt(bounds.maxX(), bounds.maxY(), bounds.maxZ()).getLocation())) {
            throw new IllegalArgumentException("That selection is outside the world border.");
        }
    }

    private record ClipboardData(Bounds bounds, List<SpecialBlock> blocks) {}

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds of(final int x1, final int y1, final int z1,
                         final int x2, final int y2, final int z2) {
            return new Bounds(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        long sizeX() { return (long) maxX - minX + 1; }
        long sizeY() { return (long) maxY - minY + 1; }
        long sizeZ() { return (long) maxZ - minZ + 1; }

        long volume() {
            final long xy;
            try {
                xy = Math.multiplyExact(sizeX(), sizeY());
                return Math.multiplyExact(xy, sizeZ());
            } catch (final ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        boolean contains(final int x, final int y, final int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }
}
