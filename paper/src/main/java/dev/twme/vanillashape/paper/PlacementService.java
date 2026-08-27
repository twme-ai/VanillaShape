package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.PlacementFace;
import dev.twme.vanillashape.common.PlacementResolver;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class PlacementService {
    private final BlockService blocks;

    PlacementService(final BlockService blocks) {
        this.blocks = blocks;
    }

    SpecialBlock place(final Player player, final Block target, final SpecialBlock template,
                       final boolean consumeItem) {
        return placeResolved(player, target, template, consumeItem);
    }

    SpecialBlock place(final Player player, final Block target, final SpecialBlock template,
                       final boolean consumeItem, final PlacementFace clickedFace,
                       final float hitX, final float hitY, final float hitZ) {
        SpecialBlock resolved = PlacementResolver.forPlacement(
                template, PlacementResolver.playerFacing(player.getLocation().getYaw()),
                clickedFace, hitX, hitY, hitZ);
        if (resolved.shape() == ShapeType.MODEL) resolved = orientModel(resolved, player, clickedFace);
        return placeResolved(player, target, resolved, consumeItem);
    }

    private static SpecialBlock orientModel(final SpecialBlock template, final Player player,
                                            final PlacementFace clickedFace) {
        final BlockData data = org.bukkit.Bukkit.createBlockData(template.model());
        final BlockFace outward = bukkitFace(clickedFace);
        if (data instanceof FaceAttachable attachable) {
            attachable.setAttachedFace(switch (clickedFace) {
                case UP -> FaceAttachable.AttachedFace.FLOOR;
                case DOWN -> FaceAttachable.AttachedFace.CEILING;
                default -> FaceAttachable.AttachedFace.WALL;
            });
        }
        if (data instanceof Directional directional) {
            final BlockFace facing = switch (clickedFace) {
                case NORTH, EAST, SOUTH, WEST -> outward;
                default -> playerFacing(player);
            };
            if (directional.getFaces().contains(facing)) directional.setFacing(facing);
        }
        if (data instanceof Rotatable rotatable) rotatable.setRotation(playerFacing(player));
        if (data instanceof MultipleFacing multiple) {
            for (final BlockFace face : multiple.getAllowedFaces()) multiple.setFace(face, false);
            final BlockFace attachment = outward.getOppositeFace();
            if (multiple.getAllowedFaces().contains(attachment)) multiple.setFace(attachment, true);
        }
        return template.withModel(data.getAsString());
    }

    private static BlockFace playerFacing(final Player player) {
        return switch (PlacementResolver.playerFacing(player.getLocation().getYaw())) {
            case NORTH -> BlockFace.NORTH; case EAST -> BlockFace.EAST;
            case SOUTH -> BlockFace.SOUTH; case WEST -> BlockFace.WEST;
        };
    }

    private static BlockFace bukkitFace(final PlacementFace face) {
        return switch (face) {
            case NORTH -> BlockFace.NORTH; case EAST -> BlockFace.EAST;
            case SOUTH -> BlockFace.SOUTH; case WEST -> BlockFace.WEST;
            case UP -> BlockFace.UP; case DOWN -> BlockFace.DOWN;
        };
    }

    private SpecialBlock placeResolved(final Player player, final Block target,
                                       final SpecialBlock template, final boolean consumeItem) {
        final String world = BlockService.worldKey(target.getWorld());
        ensureEmpty(target, world, null);
        if (template.shape() == ShapeType.DOOR) {
            final Block upper = target.getRelative(0, 1, 0);
            ensureEmpty(upper, world, null);
        }
        final SpecialBlock placed = template.at(world, target.getX(), target.getY(), target.getZ())
                .withFlags(template.flags() & ~SpecialBlock.DOOR_UPPER);
        blocks.put(placed);
        if (placed.shape() == ShapeType.DOOR) {
            blocks.put(placed.at(world, placed.x(), placed.y() + 1, placed.z())
                    .withFlags(placed.flags() | SpecialBlock.DOOR_UPPER));
        }
        if (consumeItem && player.getGameMode() != GameMode.CREATIVE) {
            final ItemStack held = player.getInventory().getItemInMainHand();
            held.subtract(1);
        }
        return placed;
    }

    SpecialBlock replace(final Player player, final SpecialBlock target,
                         final SpecialBlock template, final boolean consumeItem) {
        final SpecialBlock base = lowerDoorHalf(target);
        final World world = player.getWorld();
        final Block position = world.getBlockAt(base.x(), base.y(), base.z());
        if (!position.getType().isAir()) throw new IllegalArgumentException("The backing block is not air.");
        if (template.shape() == ShapeType.DOOR) {
            final Block upper = position.getRelative(0, 1, 0);
            final SpecialBlock existingUpper = blocks.get(base.world(), base.x(), base.y() + 1, base.z());
            ensureEmpty(upper, base.world(), existingUpper);
        }
        blocks.removeStructure(base);
        return place(player, position, template, consumeItem);
    }

    void restore(final SpecialBlock target, final World world) {
        final SpecialBlock base = lowerDoorHalf(target);
        final Block lower = world.getBlockAt(base.x(), base.y(), base.z());
        if (!lower.getType().isAir()) throw new IllegalArgumentException("The backing block is not air.");
        if (base.shape() == ShapeType.DOOR) {
            final Block upper = lower.getRelative(0, 1, 0);
            if (!upper.getType().isAir()) throw new IllegalArgumentException("The upper backing block is not air.");
        }
        blocks.removeStructure(base);
        lower.setBlockData(org.bukkit.Bukkit.createBlockData(
                base.shape() == ShapeType.MODEL ? base.model() : base.material()), false);
        if (base.shape() == ShapeType.DOOR) {
            lower.getRelative(0, 1, 0).setBlockData(org.bukkit.Bukkit.createBlockData(base.material()), false);
        }
    }

    private SpecialBlock lowerDoorHalf(final SpecialBlock block) {
        if (block.shape() != ShapeType.DOOR || (block.flags() & SpecialBlock.DOOR_UPPER) == 0) return block;
        final SpecialBlock lower = blocks.get(block.world(), block.x(), block.y() - 1, block.z());
        return lower == null ? block.at(block.world(), block.x(), block.y() - 1, block.z())
                .withFlags(block.flags() & ~SpecialBlock.DOOR_UPPER) : lower;
    }

    private void ensureEmpty(final Block target, final String world, final SpecialBlock allowed) {
        if (target.getY() < target.getWorld().getMinHeight() || target.getY() >= target.getWorld().getMaxHeight()) {
            throw new IllegalArgumentException("The placement is outside the world height.");
        }
        if (!target.getType().isAir()) throw new IllegalArgumentException("The placement position must be air.");
        final SpecialBlock current = blocks.get(world, target.getX(), target.getY(), target.getZ());
        if (current != null && current != allowed) {
            throw new IllegalArgumentException("A VanillaShape block already exists there.");
        }
    }
}
