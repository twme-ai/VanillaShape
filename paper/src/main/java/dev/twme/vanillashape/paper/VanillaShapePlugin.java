package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.PlacementFace;
import dev.twme.vanillashape.common.WireProtocol;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.file.Files;
import java.util.Objects;
import java.util.logging.Level;

public final class VanillaShapePlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private BlockRepository repository;
    private BlockService blocks;
    private ShapeItemFactory shapeItems;
    private PlacementService placements;
    private DebugStickService debugStick;
    private InteractionService interactions;
    private WorldEditIntegration worldEdit;

    @Override public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            repository = new BlockRepository(getDataFolder().toPath().resolve("blocks.db"));
            blocks = new BlockService(this, repository);
            shapeItems = new ShapeItemFactory(this);
            placements = new PlacementService(blocks);
            debugStick = new DebugStickService(blocks);
            interactions = new InteractionService(this, blocks, shapeItems);
        } catch (final Exception error) {
            getLogger().log(Level.SEVERE, "Could not open VanillaShape block database", error);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getMessenger().registerOutgoingPluginChannel(this, WireProtocol.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, WireProtocol.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        final ShapeCommand executor = new ShapeCommand(blocks, shapeItems, placements, interactions);
        final PluginCommand command = Objects.requireNonNull(getCommand("vshape"));
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")
                || Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")) {
            try {
                worldEdit = WorldEditIntegration.enable(this, blocks);
            } catch (final LinkageError | RuntimeException error) {
                getLogger().log(Level.SEVERE, "Could not enable WorldEdit/FAWE compatibility", error);
            }
        }
        getLogger().info("Loaded " + blocks.inWorld("minecraft:overworld").size()
                + " overworld special blocks; Fabric clients may now synchronize.");
        if (Bukkit.getPluginManager().isPluginEnabled("AxiomPaper")) {
            getLogger().info("AxiomPaper detected; VanillaShape Axiom edits also honor Axiom editor permissions.");
        }
    }

    @Override public void onDisable() {
        if (worldEdit != null) worldEdit.close();
        if (repository != null) repository.close();
    }

    @Override public void onPluginMessageReceived(
            final String channel, final Player player, final byte[] message) {
        if (!WireProtocol.CHANNEL.equals(channel) || message.length < 2 || message.length > 1024) return;
        if (!Bukkit.isPrimaryThread()) {
            final byte[] copy = message.clone();
            Bukkit.getScheduler().runTask(this, () -> onPluginMessageReceived(channel, player, copy));
            return;
        }
        try {
            handleClientAction(player, WireProtocol.decode(message));
        } catch (final IllegalArgumentException error) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(error.getMessage(),
                    net.kyori.adventure.text.format.NamedTextColor.RED));
        } catch (final Exception error) {
            getLogger().log(Level.WARNING, "Rejected malformed VanillaShape payload from " + player.getName(), error);
        }
    }

    private void handleClientAction(final Player player, final WireProtocol.Decoded request) {
        switch (request.action()) {
            case WireProtocol.HELLO -> blocks.sync(player);
            case WireProtocol.DEBUG_SELECT, WireProtocol.DEBUG_CYCLE -> {
                requirePermission(player, "vanillashape.debugstick");
                if (player.getInventory().getItemInMainHand().getType() != Material.DEBUG_STICK) {
                    throw new IllegalArgumentException("Hold a debug stick to edit VanillaShape states.");
                }
                final var target = requireNearTarget(player, request.x(), request.y(), request.z());
                if (request.action() == WireProtocol.DEBUG_SELECT) {
                    debugStick.select(player, target, request.reverse());
                } else {
                    debugStick.cycle(player, target, request.reverse());
                }
            }
            case WireProtocol.PLACE_ITEM -> {
                requirePermission(player, "vanillashape.use");
                // A VanillaShape item normally places beside the rendered block. While material
                // replacement mode is active, reinterpret the same click as an interaction with
                // that supporting block so both ordinary block items and shape items can supply
                // the replacement material.
                if (interactions.replacementMode(player)) {
                    final int[] support = supportingPosition(request.x(), request.y(), request.z(), request.face());
                    interactions.interact(player, requireNearTarget(player,
                            support[0], support[1], support[2]));
                    break;
                }
                requireNear(player, request.x(), request.y(), request.z());
                placeHeld(player, request.x(), request.y(), request.z(), true,
                        request.face(), request.hitX(), request.hitY(), request.hitZ());
            }
            case WireProtocol.PICK_ITEM -> {
                requirePermission(player, "vanillashape.items");
                final var target = requireNearTarget(player, request.x(), request.y(), request.z());
                pick(player, target);
            }
            case WireProtocol.BREAK_BLOCK -> {
                requirePermission(player, "vanillashape.break");
                final var target = requireNearTarget(player, request.x(), request.y(), request.z());
                breakBlock(player, target);
            }
            case WireProtocol.INTERACT_BLOCK -> {
                requirePermission(player, "vanillashape.use");
                final var target = requireNearTarget(player, request.x(), request.y(), request.z());
                interactions.interact(player, target);
            }
            case WireProtocol.AXIOM_PLACE -> {
                requireAxiom(player, true);
                validateWorldPosition(player, request.x(), request.y(), request.z());
                placeHeld(player, request.x(), request.y(), request.z(), true,
                        request.face(), request.hitX(), request.hitY(), request.hitZ());
            }
            case WireProtocol.AXIOM_REPLACE -> {
                requireAxiom(player, true);
                validateWorldPosition(player, request.x(), request.y(), request.z());
                final var target = requireTarget(player, request.x(), request.y(), request.z());
                final var template = shapeItems.read(player.getInventory().getItemInMainHand())
                        .orElseThrow(() -> new IllegalArgumentException("Hold a VanillaShape item to replace with it."));
                placements.replace(player, target, template, true);
            }
            case WireProtocol.AXIOM_DELETE -> {
                requireAxiom(player, false);
                validateWorldPosition(player, request.x(), request.y(), request.z());
                blocks.removeStructure(requireTarget(player, request.x(), request.y(), request.z()));
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onUseShapeItem(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        final ItemStack item = event.getItem();
        final var shape = shapeItems.read(item);
        if (shape.isEmpty()) return;
        event.setCancelled(true);
        final Player player = event.getPlayer();
        try {
            requirePermission(player, "vanillashape.use");
            final Block clicked = Objects.requireNonNull(event.getClickedBlock());
            final Block target = clicked.getRelative(Objects.requireNonNull(event.getBlockFace()));
            final org.bukkit.util.Vector hit = event.getClickedPosition() == null
                    ? new org.bukkit.util.Vector(.5, .5, .5) : event.getClickedPosition();
            placements.place(player, target, shape.get(), true, face(event.getBlockFace()),
                    unit(hit.getX()), unit(hit.getY()), unit(hit.getZ()));
        } catch (final IllegalArgumentException error) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(error.getMessage(),
                    net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    @EventHandler public void onJoin(final PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> blocks.sync(event.getPlayer()), 20L);
    }

    @EventHandler public void onWorldChange(final PlayerChangedWorldEvent event) {
        blocks.sync(event.getPlayer());
    }

    @EventHandler public void onQuit(final PlayerQuitEvent event) {
        interactions.forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanillaBlockPlaced(final BlockPlaceEvent event) {
        final Block changed = event.getBlockPlaced();
        blocks.neighborChanged(changed.getWorld(), changed.getX(), changed.getY(), changed.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanillaBlockBroken(final BlockBreakEvent event) {
        final Block changed = event.getBlock();
        Bukkit.getScheduler().runTask(this, () -> blocks.neighborChanged(
                changed.getWorld(), changed.getX(), changed.getY(), changed.getZ()));
    }

    private void placeHeld(final Player player, final int x, final int y, final int z,
                           final boolean consume, final PlacementFace face,
                           final float hitX, final float hitY, final float hitZ) {
        final var template = shapeItems.read(player.getInventory().getItemInMainHand())
                .orElseThrow(() -> new IllegalArgumentException("Hold a VanillaShape item first."));
        placements.place(player, player.getWorld().getBlockAt(x, y, z), template, consume,
                Objects.requireNonNull(face, "placement face"), hitX, hitY, hitZ);
    }

    private void pick(final Player player, final dev.twme.vanillashape.common.SpecialBlock target) {
        final ItemStack item = shapeItems.create(target, 1);
        final int existing = player.getInventory().first(item);
        if (existing >= 0) {
            player.getInventory().setHeldItemSlot(existing);
        } else if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            player.getInventory().setItemInMainHand(item);
        } else {
            final var overflow = player.getInventory().addItem(item);
            overflow.values().forEach(value -> player.getWorld().dropItemNaturally(player.getLocation(), value));
        }
    }

    private void breakBlock(final Player player,
                            final dev.twme.vanillashape.common.SpecialBlock target) {
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            throw new IllegalArgumentException("Spectators cannot break VanillaShape blocks.");
        }
        final Block backing = player.getWorld().getBlockAt(target.x(), target.y(), target.z());
        final BlockBreakEvent event = new BlockBreakEvent(backing, player);
        event.setDropItems(false);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) throw new IllegalArgumentException("That block is protected.");

        final var droppedState = lowerDoorHalf(target);
        blocks.removeStructure(target);
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            player.getWorld().dropItemNaturally(backing.getLocation().add(.5, .5, .5),
                    shapeItems.create(droppedState, 1));
        }
    }

    private dev.twme.vanillashape.common.SpecialBlock lowerDoorHalf(
            final dev.twme.vanillashape.common.SpecialBlock block) {
        if (block.shape() != dev.twme.vanillashape.common.ShapeType.DOOR
                || (block.flags() & dev.twme.vanillashape.common.SpecialBlock.DOOR_UPPER) == 0) {
            return block;
        }
        final var lower = blocks.get(block.world(), block.x(), block.y() - 1, block.z());
        return lower == null ? block.at(block.world(), block.x(), block.y() - 1, block.z())
                .withFlags(block.flags() & ~dev.twme.vanillashape.common.SpecialBlock.DOOR_UPPER) : lower;
    }

    private dev.twme.vanillashape.common.SpecialBlock requireNearTarget(
            final Player player, final int x, final int y, final int z) {
        requireNear(player, x, y, z);
        return requireTarget(player, x, y, z);
    }

    private dev.twme.vanillashape.common.SpecialBlock requireTarget(
            final Player player, final int x, final int y, final int z) {
        final var target = blocks.get(BlockService.worldKey(player.getWorld()), x, y, z);
        if (target == null) throw new IllegalArgumentException("That VanillaShape block no longer exists.");
        return target;
    }

    private static void requireNear(final Player player, final int x, final int y, final int z) {
        final double dx = player.getEyeLocation().getX() - (x + .5);
        final double dy = player.getEyeLocation().getY() - (y + .5);
        final double dz = player.getEyeLocation().getZ() - (z + .5);
        if (dx * dx + dy * dy + dz * dz > 100) {
            throw new IllegalArgumentException("That block is out of interaction range.");
        }
    }

    private static void validateWorldPosition(final Player player, final int x, final int y, final int z) {
        if (y < player.getWorld().getMinHeight() || y >= player.getWorld().getMaxHeight()) {
            throw new IllegalArgumentException("That position is outside the world height.");
        }
        final var border = player.getWorld().getWorldBorder();
        if (!border.isInside(player.getWorld().getBlockAt(x, y, z).getLocation())) {
            throw new IllegalArgumentException("That position is outside the world border.");
        }
    }

    private static void requirePermission(final Player player, final String permission) {
        if (!player.hasPermission(permission)) throw new IllegalArgumentException("You do not have permission.");
    }

    private void requireAxiom(final Player player, final boolean placesBlocks) {
        requirePermission(player, "vanillashape.axiom");
        if (!Bukkit.getPluginManager().isPluginEnabled("AxiomPaper")) return;
        if (!player.hasPermission("axiom.editor.use")
                || (placesBlocks && !player.hasPermission("axiom.build.place"))) {
            throw new IllegalArgumentException("AxiomPaper does not allow this editor operation.");
        }
    }

    private static PlacementFace face(final org.bukkit.block.BlockFace face) {
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

    private static int[] supportingPosition(final int x, final int y, final int z,
                                            final PlacementFace face) {
        return switch (face) {
            case NORTH -> new int[] {x, y, z + 1};
            case EAST -> new int[] {x - 1, y, z};
            case SOUTH -> new int[] {x, y, z - 1};
            case WEST -> new int[] {x + 1, y, z};
            case UP -> new int[] {x, y - 1, z};
            case DOWN -> new int[] {x, y + 1, z};
        };
    }

    private static float unit(final double value) {
        return (float) Math.max(0, Math.min(1, value));
    }
}
