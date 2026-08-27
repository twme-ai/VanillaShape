package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
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

    ShapeCommand(final BlockService blocks) { this.blocks = blocks; }

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

        final String material = args.length >= 3
                ? parseMaterial(join(args, 2)).getAsString()
                : heldMaterial(player).getAsString();
        final Direction facing = facing(player);
        final String world = BlockService.worldKey(target.getWorld());
        final SpecialBlock lower = new SpecialBlock(world, target.getX(), target.getY(), target.getZ(),
                shape, material, facing, CornerShape.STRAIGHT, 0);

        if (shape == ShapeType.DOOR) {
            final Block upperBlock = target.getRelative(BlockFace.UP);
            if (!upperBlock.getType().isAir() || blocks.get(world, upperBlock.getX(), upperBlock.getY(), upperBlock.getZ()) != null) {
                throw new IllegalArgumentException("A door needs two empty blocks of height.");
            }
            blocks.put(lower);
            blocks.put(new SpecialBlock(world, target.getX(), target.getY() + 1, target.getZ(),
                    shape, material, facing, CornerShape.STRAIGHT, SpecialBlock.DOOR_UPPER));
        } else {
            blocks.put(lower);
        }
        player.sendMessage("§aPlaced " + shape.name().toLowerCase(Locale.ROOT)
                + " at " + target.getX() + " " + target.getY() + " " + target.getZ() + ".");
        return true;
    }

    private boolean remove(final Player player) {
        final SpecialBlock target = requireTarget(player);
        blocks.remove(target.world(), target.x(), target.y(), target.z());
        if (target.shape() == ShapeType.DOOR) {
            final int counterpartY = (target.flags() & SpecialBlock.DOOR_UPPER) != 0
                    ? target.y() - 1 : target.y() + 1;
            blocks.remove(target.world(), target.x(), counterpartY, target.z());
        }
        player.sendMessage("§aRemoved the special block.");
        return true;
    }

    private boolean material(final Player player, final String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /vshape material <blockdata>");
        final SpecialBlock target = requireTarget(player);
        final String value = parseMaterial(join(args, 1)).getAsString();
        blocks.put(target.withMaterial(value));
        if (target.shape() == ShapeType.DOOR) updateDoorCounterpart(target, block -> block.withMaterial(value));
        player.sendMessage("§aMaterial set to " + value + ".");
        return true;
    }

    private boolean state(final Player player, final String[] args) {
        if (args.length != 3) throw new IllegalArgumentException(
                "Usage: /vshape state <facing|top|open|hinge|waterlogged> <value>");
        final SpecialBlock target = requireTarget(player);
        final String property = args[1].toLowerCase(Locale.ROOT);
        final String value = args[2].toLowerCase(Locale.ROOT);
        final SpecialBlock updated;
        if (property.equals("facing")) {
            try { updated = target.withFacing(Direction.valueOf(value.toUpperCase(Locale.ROOT))); }
            catch (final RuntimeException error) { throw new IllegalArgumentException("Facing must be north/east/south/west."); }
        } else {
            final int bit = switch (property) {
                case "top" -> SpecialBlock.TOP;
                case "open" -> SpecialBlock.OPEN;
                case "waterlogged" -> SpecialBlock.WATERLOGGED;
                case "hinge" -> SpecialBlock.HINGE_RIGHT;
                default -> throw new IllegalArgumentException("Unknown state property: " + property);
            };
            final boolean enabled = property.equals("hinge")
                    ? switch (value) { case "right" -> true; case "left" -> false;
                        default -> throw new IllegalArgumentException("Hinge must be left or right."); }
                    : parseBoolean(value);
            updated = target.withFlags(enabled ? target.flags() | bit : target.flags() & ~bit);
        }
        blocks.put(updated);
        if (target.shape() == ShapeType.DOOR) {
            updateDoorCounterpart(target, block -> new SpecialBlock(block.world(), block.x(), block.y(), block.z(),
                    block.shape(), updated.material(), updated.facing(), updated.corner(),
                    (updated.flags() & ~SpecialBlock.DOOR_UPPER) | (block.flags() & SpecialBlock.DOOR_UPPER)));
        }
        player.sendMessage("§aUpdated " + property + ".");
        return true;
    }

    private boolean inspect(final Player player) {
        final SpecialBlock block = requireTarget(player);
        player.sendMessage("§e" + block.shape() + " §7@ " + block.x() + " " + block.y() + " " + block.z());
        player.sendMessage("§7material=" + block.material() + ", facing=" + block.facing()
                + ", corner=" + block.corner() + ", flags=" + block.flags());
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
        player.sendMessage("§e/vshape place <wall|fence|fence_gate|slab|stairs|door|trapdoor|vertical_slab> [blockdata]");
        player.sendMessage("§e/vshape remove §7— remove the rendered block in your crosshair");
        player.sendMessage("§e/vshape material <blockdata>");
        player.sendMessage("§e/vshape state <facing|top|open|hinge|waterlogged> <value>");
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
        if (other != null && other.shape() == ShapeType.DOOR) blocks.put(change.apply(other));
    }

    private static BlockData heldMaterial(final Player player) {
        final ItemStack item = player.getInventory().getItemInMainHand();
        final Material type = item.getType();
        if (!type.isBlock() || type.isAir()) return Material.STONE.createBlockData();
        if (item.getItemMeta() instanceof BlockDataMeta meta) return meta.getBlockData(type);
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

    private static boolean parseBoolean(final String value) {
        return switch (value) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("Value must be true or false.");
        };
    }

    private static String join(final String[] args, final int start) {
        return String.join("", Arrays.copyOfRange(args, start, args.length));
    }

    @Override public List<String> onTabComplete(
            final CommandSender sender, final Command command, final String alias, final String[] args) {
        if (args.length == 1) return match(args[0], "place", "remove", "material", "state", "inspect", "list");
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            return match(args[1], Arrays.stream(ShapeType.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toArray(String[]::new));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("state")) {
            return match(args[1], "facing", "top", "open", "hinge", "waterlogged");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("state")) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "facing" -> match(args[2], "north", "east", "south", "west");
                case "hinge" -> match(args[2], "left", "right");
                default -> match(args[2], "true", "false");
            };
        }
        return List.of();
    }

    private static List<String> match(final String prefix, final String... values) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        return Arrays.stream(values).filter(value -> value.startsWith(lower)).toList();
    }
}
