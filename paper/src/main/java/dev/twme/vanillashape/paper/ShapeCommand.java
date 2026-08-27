package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.PlacementFace;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.StateSchema;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.util.RayTraceResult;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class ShapeCommand implements CommandExecutor, TabCompleter {
    private final BlockService blocks;
    private final ShapeItemFactory shapeItems;
    private final PlacementService placements;
    private final InteractionService interactions;

    ShapeCommand(final BlockService blocks, final ShapeItemFactory shapeItems,
                 final PlacementService placements, final InteractionService interactions) {
        this.blocks = blocks;
        this.shapeItems = shapeItems;
        this.placements = placements;
        this.interactions = interactions;
    }

    @Override public boolean onCommand(
            final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command currently requires a player.");
            return true;
        }
        if (args.length == 0) return help(player);
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "place" -> place(player, args);
                case "remove" -> remove(player);
                case "material" -> material(player, args);
                case "state" -> state(player, args);
                case "give" -> give(player, args);
                case "palette" -> palette(player, args);
                case "replace" -> replace(player, args);
                case "replacemode" -> replaceMode(player, args);
                case "convert" -> convert(player, args);
                case "restore" -> restore(player);
                case "inspect" -> inspect(player);
                case "list" -> list(player);
                default -> help(player);
            };
        } catch (final IllegalArgumentException error) {
            player.sendMessage("§c" + error.getMessage());
            return true;
        } catch (final IllegalStateException error) {
            player.sendMessage("§cStorage error: " + error.getMessage());
            return true;
        }
    }

    private boolean place(final Player player, final String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /vshape place <shape> [blockdata]");
        final ShapeType shape;
        try { shape = ShapeType.parse(args[1]); }
        catch (final RuntimeException error) { throw new IllegalArgumentException("Unknown shape: " + args[1]); }

        final RayTraceResult hit = player.rayTraceBlocks(7, FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            throw new IllegalArgumentException("Look at the face of a block to choose the placement position.");
        }
        final Block target = hit.getHitBlock().getRelative(hit.getHitBlockFace());
        if (!target.getType().isAir()) throw new IllegalArgumentException("The placement position must be air.");
        if (blocks.get(BlockService.worldKey(target.getWorld()), target.getX(), target.getY(), target.getZ()) != null) {
            throw new IllegalArgumentException("A special block already exists there.");
        }

        final String model = shape == ShapeType.MODEL
                ? args.length >= 3 ? parseMaterial(args[2]).getAsString()
                : heldMaterial(player).getAsString() : "";
        final String material = shape == ShapeType.MODEL
                ? args.length >= 4 ? parseMaterial(args[3]).getAsString() : heldMaterial(player).getAsString()
                : args.length >= 3 ? parseMaterial(join(args, 2)).getAsString() : heldMaterial(player).getAsString();
        final Direction facing = facing(player);
        final String world = BlockService.worldKey(target.getWorld());
        final SpecialBlock lower = new SpecialBlock(world, target.getX(), target.getY(), target.getZ(),
                shape, material, model, facing, CornerShape.STRAIGHT, 0);

        final org.bukkit.util.Vector point = hit.getHitPosition();
        final Block support = hit.getHitBlock();
        placements.place(player, target, lower, false, face(hit.getHitBlockFace()),
                unit(point.getX() - support.getX()), unit(point.getY() - support.getY()),
                unit(point.getZ() - support.getZ()));
        player.sendMessage("§aPlaced " + shape.name().toLowerCase(Locale.ROOT)
                + " at " + target.getX() + " " + target.getY() + " " + target.getZ() + ".");
        return true;
    }

    private boolean remove(final Player player) {
        final SpecialBlock target = requireTarget(player);
        blocks.removeStructure(target);
        player.sendMessage("§aRemoved the special block.");
        return true;
    }

    private boolean material(final Player player, final String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /vshape material <blockdata>");
        final SpecialBlock target = requireTarget(player);
        final String value = parseMaterial(join(args, 1)).getAsString();
        blocks.putExact(target.withMaterial(value));
        if (target.shape() == ShapeType.DOOR) updateDoorCounterpart(target, block -> block.withMaterial(value));
        player.sendMessage("§aMaterial set to " + value + ".");
        return true;
    }

    private boolean state(final Player player, final String[] args) {
        if (args.length != 3) throw new IllegalArgumentException(
                "Usage: /vshape state <property> <value>");
        final SpecialBlock target = requireTarget(player);
        final String property = args[1].toLowerCase(Locale.ROOT);
        final String value = args[2].toLowerCase(Locale.ROOT);
        final SpecialBlock updated = StateSchema.withValue(target, property, value);
        blocks.putExact(updated);
        if (target.shape() == ShapeType.DOOR) {
            updateDoorCounterpart(target, block -> new SpecialBlock(block.world(), block.x(), block.y(), block.z(),
                    block.shape(), updated.material(), updated.facing(), updated.corner(),
                    (updated.flags() & ~SpecialBlock.DOOR_UPPER) | (block.flags() & SpecialBlock.DOOR_UPPER)));
        }
        player.sendMessage("§aUpdated " + property + ".");
        return true;
    }

    private boolean give(final Player player, final String[] args) {
        if (args.length < 2 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Usage: /vshape give <shape> [blockdata] [amount], or give model <model> [material] [amount]");
        }
        final ShapeType shape = parseShape(args[1]);
        final String model = shape == ShapeType.MODEL
                ? args.length >= 3 ? parseMaterial(args[2]).getAsString() : heldMaterial(player).getAsString() : "";
        final String material = shape == ShapeType.MODEL
                ? args.length >= 4 ? parseMaterial(args[3]).getAsString() : heldMaterial(player).getAsString()
                : args.length >= 3 ? parseMaterial(args[2]).getAsString() : heldMaterial(player).getAsString();
        final int amount = shape == ShapeType.MODEL
                ? args.length == 5 ? parseAmount(args[4]) : 1
                : args.length == 4 ? parseAmount(args[3]) : 1;
        giveItem(player, template(player, shape, material).withModel(model), amount);
        player.sendMessage("§aAdded a " + shape.name().toLowerCase(Locale.ROOT) + " item to your inventory.");
        return true;
    }

    private boolean palette(final Player player, final String[] args) {
        if (args.length > 2) throw new IllegalArgumentException("Usage: /vshape palette [blockdata]");
        final String material = args.length == 2 ? parseMaterial(args[1]).getAsString()
                : heldMaterial(player).getAsString();
        for (final ShapeType shape : ShapeType.values()) {
            if (shape != ShapeType.MODEL) giveItem(player, template(player, shape, material), 1);
        }
        player.sendMessage("§aAdded all fixed VanillaShape blocks to your inventory. Use 'give model' for any vanilla model.");
        return true;
    }

    private boolean replace(final Player player, final String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /vshape replace <shape> [blockdata]");
        final SpecialBlock target = requireTarget(player);
        final ShapeType shape = parseShape(args[1]);
        final String model = shape == ShapeType.MODEL
                ? args.length >= 3 ? parseMaterial(args[2]).getAsString()
                : target.shape() == ShapeType.MODEL ? target.model() : target.material() : "";
        final String material = shape == ShapeType.MODEL
                ? args.length >= 4 ? parseMaterial(args[3]).getAsString() : target.material()
                : args.length >= 3 ? parseMaterial(join(args, 2)).getAsString() : target.material();
        final SpecialBlock replacement = new SpecialBlock(target.world(), target.x(), target.y(), target.z(),
                shape, material, model, target.facing(), target.corner(), target.flags() & ~SpecialBlock.DOOR_UPPER);
        placements.replace(player, target, replacement, false);
        player.sendMessage("§aReplaced the target with " + shape.name().toLowerCase(Locale.ROOT) + ".");
        return true;
    }

    private boolean replaceMode(final Player player, final String[] args) {
        if (args.length > 2) throw new IllegalArgumentException("Usage: /vshape replacemode [on|off|toggle]");
        final Boolean requested;
        if (args.length == 1 || args[1].equalsIgnoreCase("toggle")) requested = null;
        else if (args[1].equalsIgnoreCase("on")) requested = true;
        else if (args[1].equalsIgnoreCase("off")) requested = false;
        else throw new IllegalArgumentException("Replacement mode must be on, off, or toggle.");
        final boolean enabled = interactions.setReplacementMode(player, requested);
        player.sendMessage(enabled
                ? "§aReplacement mode enabled. Right-click a VanillaShape block with a block item."
                : "§eReplacement mode disabled.");
        return true;
    }

    private boolean convert(final Player player, final String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /vshape convert <shape> [blockdata]");
        final ShapeType shape = parseShape(args[1]);
        final RayTraceResult hit = player.rayTraceBlocks(7, FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null) {
            throw new IllegalArgumentException("Look at a vanilla block to convert it.");
        }
        final Block target = hit.getHitBlock();
        if (target.getType().isAir()) throw new IllegalArgumentException("Look at a non-air vanilla block.");
        final String world = BlockService.worldKey(target.getWorld());
        if (blocks.get(world, target.getX(), target.getY(), target.getZ()) != null) {
            throw new IllegalArgumentException("A VanillaShape block already exists there.");
        }
        final BlockData original = target.getBlockData();
        final String material = args.length >= 3 ? parseMaterial(join(args, 2)).getAsString() : original.getAsString();
        target.setType(Material.AIR, false);
        try {
            final org.bukkit.util.Vector point = hit.getHitPosition();
            // Conversion occupies the clicked half of this same block; PlacementResolver normally
            // receives the outward face of a separate support block, so invert it here.
            final SpecialBlock template = template(player, shape, material)
                    .withModel(shape == ShapeType.MODEL ? original.getAsString() : "");
            placements.place(player, target, template, false,
                    face(hit.getHitBlockFace().getOppositeFace()), unit(point.getX() - target.getX()),
                    unit(point.getY() - target.getY()), unit(point.getZ() - target.getZ()));
        } catch (final RuntimeException error) {
            target.setBlockData(original, false);
            throw error;
        }
        player.sendMessage("§aConverted the vanilla block into a VanillaShape "
                + shape.name().toLowerCase(Locale.ROOT) + ".");
        return true;
    }

    private boolean restore(final Player player) {
        final SpecialBlock target = requireTarget(player);
        placements.restore(target, player.getWorld());
        player.sendMessage("§aRestored the material as vanilla backing block(s).");
        return true;
    }

    private boolean inspect(final Player player) {
        final SpecialBlock block = requireTarget(player);
        player.sendMessage("§e" + block.shape() + " §7@ " + block.x() + " " + block.y() + " " + block.z());
        player.sendMessage("§7material=" + block.material() + ", facing=" + block.facing()
                + ", corner=" + block.corner() + ", flags=" + block.flags());
        if (block.shape() == ShapeType.MODEL) player.sendMessage("§7model=" + block.model());
        return true;
    }

    private boolean list(final Player player) {
        final var entries = blocks.inWorld(BlockService.worldKey(player.getWorld()));
        player.sendMessage("§eVanillaShape blocks in this world: " + entries.size());
        entries.stream().limit(20).forEach(block -> player.sendMessage("§7- "
                + block.shape() + " " + block.x() + " " + block.y() + " " + block.z()
                + " (" + block.material() + ")"));
        if (entries.size() > 20) player.sendMessage("§7… and " + (entries.size() - 20) + " more.");
        return true;
    }

    private boolean help(final Player player) {
        player.sendMessage("§e/vshape place <fixed-shape> [material]");
        player.sendMessage("§e/vshape place model <vanilla-blockdata> [material]");
        player.sendMessage("§e/vshape remove §7— remove the rendered block in your crosshair");
        player.sendMessage("§e/vshape material <blockdata>");
        player.sendMessage("§e/vshape state <property> <value>");
        player.sendMessage("§e/vshape give <shape> [blockdata] [amount] | give model <model> [material] [amount]");
        player.sendMessage("§e/vshape palette [blockdata]");
        player.sendMessage("§e/vshape replace <shape> [blockdata] | replacemode [on|off|toggle]");
        player.sendMessage("§e/vshape convert <shape> [blockdata] | restore");
        player.sendMessage("§e/vshape inspect | list");
        return true;
    }

    private SpecialBlock requireTarget(final Player player) {
        final SpecialBlock target = blocks.raycast(player, 7);
        if (target == null) throw new IllegalArgumentException("Look directly at a VanillaShape block.");
        return target;
    }

    private void updateDoorCounterpart(final SpecialBlock target,
            final java.util.function.UnaryOperator<SpecialBlock> change) {
        final int otherY = (target.flags() & SpecialBlock.DOOR_UPPER) != 0 ? target.y() - 1 : target.y() + 1;
        final SpecialBlock other = blocks.get(target.world(), target.x(), otherY, target.z());
        if (other != null && other.shape() == ShapeType.DOOR) blocks.putExact(change.apply(other));
    }

    private static BlockData heldMaterial(final Player player) {
        final ItemStack item = player.getInventory().getItemInMainHand();
        final Material type = item.getType();
        if (!type.isBlock() || type.isAir()) {
            throw new IllegalArgumentException("Hold a non-air block item or specify a material.");
        }
        if (item.getItemMeta() instanceof BlockDataMeta meta && meta.hasBlockData()) return meta.getBlockData(type);
        return type.createBlockData();
    }

    private static BlockData parseMaterial(final String input) {
        final String normalized = input.indexOf(':') >= 0 ? input : "minecraft:" + input;
        final BlockData data = Bukkit.createBlockData(normalized);
        if (!data.getMaterial().isBlock() || data.getMaterial().isAir()) {
            throw new IllegalArgumentException("Material must be a non-air block.");
        }
        return data;
    }

    private static Direction facing(final Player player) {
        final int quadrant = Math.floorMod(Math.round(player.getLocation().getYaw() / 90.0f), 4);
        return switch (quadrant) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private static String join(final String[] args, final int start) {
        return String.join("", Arrays.copyOfRange(args, start, args.length));
    }

    @Override public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (args.length == 1) return match(args[0], "place", "remove", "material", "state", "give",
                "palette", "replace", "replacemode", "convert", "restore", "inspect", "list");
        if (args.length == 2 && args[0].equalsIgnoreCase("replacemode")) {
            return match(args[1], "on", "off", "toggle");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("place")
                || args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("replace")
                || args[0].equalsIgnoreCase("convert"))) {
            return match(args[1], Arrays.stream(ShapeType.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toArray(String[]::new));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("state")) {
            return match(args[1], "facing", "half", "corner", "open", "hinge", "powered",
                    "waterlogged", "north", "east", "south", "west");
        }
        return List.of();
    }

    private void giveItem(final Player player, final SpecialBlock template, final int amount) {
        final var overflow = player.getInventory().addItem(shapeItems.create(template, amount));
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private static SpecialBlock template(final Player player, final ShapeType shape, final String material) {
        return new SpecialBlock(BlockService.worldKey(player.getWorld()), 0, 0, 0,
                shape, material, facing(player), CornerShape.STRAIGHT, 0);
    }

    private static ShapeType parseShape(final String value) {
        try { return ShapeType.parse(value); }
        catch (final RuntimeException error) { throw new IllegalArgumentException("Unknown shape: " + value); }
    }

    private static int parseAmount(final String value) {
        try {
            final int amount = Integer.parseInt(value);
            if (amount < 1 || amount > 64) throw new NumberFormatException();
            return amount;
        } catch (final NumberFormatException error) {
            throw new IllegalArgumentException("Amount must be from 1 to 64.");
        }
    }

    private static PlacementFace face(final BlockFace face) {
        return switch (face) {
            case NORTH -> PlacementFace.NORTH;
            case EAST -> PlacementFace.EAST;
            case SOUTH -> PlacementFace.SOUTH;
            case WEST -> PlacementFace.WEST;
            case UP -> PlacementFace.UP;
            case DOWN -> PlacementFace.DOWN;
            default -> throw new IllegalArgumentException("Unsupported placement face " + face);
        };
    }

    private static float unit(final double value) {
        return (float) Math.max(0, Math.min(1, value));
    }

    private static List<String> match(final String prefix, final String... values) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        return Arrays.stream(values).filter(value -> value.startsWith(lower)).toList();
    }
}
