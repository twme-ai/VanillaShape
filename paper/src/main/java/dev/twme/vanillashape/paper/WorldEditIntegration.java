package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.command.tool.BlockTool;
import com.sk89q.worldedit.command.tool.DoubleActionBlockTool;
import com.sk89q.worldedit.command.tool.DoubleActionTraceTool;
import com.sk89q.worldedit.command.tool.Tool;
import com.sk89q.worldedit.command.tool.TraceTool;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.internal.registry.InputParser;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.session.request.Request;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.HandSide;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import dev.twme.vanillashape.common.PlacementFace;
import dev.twme.vanillashape.common.SpecialBlock;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Optional WorldEdit/FAWE bridge. This class is only loaded when either plugin is present. */
final class WorldEditIntegration implements Listener, AutoCloseable {
    private static final String FAWE_ALLOWED_CLASS = VanillaShapeExtent.class.getName();
    private static final int CLIPBOARD_WAIT_TICKS = 1_200;
    private static final double MAX_TRACE_DISTANCE_SQUARED = 512 * 512;
    private static final double NORMAL_INTERACTION_DISTANCE_SQUARED = 100;
    private static final long SAME_TOOL_TARGET_DEBOUNCE_NANOS = 150_000_000L;
    private final VanillaShapePlugin plugin;
    private final BlockService blocks;
    private final WorldEdit worldEdit;
    private final WorldEditProxyCodec codec = new WorldEditProxyCodec();
    private final boolean fawe;
    private List<String> faweAllowedPlugins;
    private boolean faweAllowedAdded;
    private Object faweHistorySettings;
    private Boolean originalCombineStages;
    private final Map<LocalSession, ClipboardWatch> clipboardWatches = new IdentityHashMap<>();
    private final Map<UUID, RecentToolClick> recentToolClicks = new HashMap<>();
    private long nextClipboardWatch;

    private WorldEditIntegration(final VanillaShapePlugin plugin, final BlockService blocks,
                                 final boolean fawe) {
        this.plugin = plugin;
        this.blocks = blocks;
        this.fawe = fawe;
        this.worldEdit = WorldEdit.getInstance();
    }

    static WorldEditIntegration enable(final VanillaShapePlugin plugin, final BlockService blocks) {
        final boolean fawe = Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")
                || classPresent("com.fastasyncworldedit.core.Fawe");
        final WorldEditIntegration integration = new WorldEditIntegration(plugin, blocks, fawe);
        if (fawe) integration.configureFawe();

        integration.worldEdit.getBlockFactory().register(new VanillaShapeBlockParser(
                integration.worldEdit, integration.codec, WorldEditIntegration::normalizeMaterial));
        integration.worldEdit.getMaskFactory().register(integration.createMaskParser());
        integration.worldEdit.getEventBus().register(integration);
        Bukkit.getPluginManager().registerEvents(integration, plugin);
        plugin.getLogger().info("Enabled VanillaShape block parser, masks, proxy extent and history bridge for "
                + (fawe ? "FastAsyncWorldEdit" : "WorldEdit") + ".");
        return integration;
    }

    @Subscribe public void onEditSession(final EditSessionEvent event) {
        if (event.getWorld() == null) return;
        final boolean mutating = event.getStage() == EditSession.Stage.BEFORE_CHANGE;
        if (!mutating && event.getStage() != EditSession.Stage.BEFORE_HISTORY) return;
        try {
            final String world = BlockService.worldKey(BukkitAdapter.adapt(event.getWorld()));
            final boolean authorized = event.getActor() == null
                    || event.getActor().hasPermission("vanillashape.worldedit");
            event.setExtent(new VanillaShapeExtent(event.getExtent(), blocks, codec,
                    world, mutating, authorized));
        } catch (final RuntimeException error) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not attach VanillaShape to a WorldEdit session for " + event.getWorld().getName(), error);
        }
    }

    /**
     * Capture clipboard commands before WorldEdit's fallback listener executes them.
     *
     * <p>The fallback command map dispatches the command from this same Bukkit
     * event and then cancels it. A MONITOR listener with {@code ignoreCancelled}
     * therefore never ran for real players even though direct EditSession tests
     * passed. LOWEST also lets a later paste repair an asynchronously completed
     * clipboard before the command consumes it.</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onWorldEditCommand(final PlayerCommandPreprocessEvent event) {
        final boolean copy = copyCommand(event.getMessage());
        final boolean schematicLoad = schematicLoadCommand(event.getMessage());
        final boolean paste = pasteCommand(event.getMessage());
        if (!copy && !schematicLoad && !paste) return;
        final var player = event.getPlayer();
        if (!player.hasPermission("vanillashape.worldedit")) return;
        final var wePlayer = BukkitAdapter.adapt(player);
        final LocalSession session = worldEdit.getSessionManager().get(wePlayer);
        if (paste) {
            patchClipboardIfReady(session);
            return;
        }
        // PlayerCommandPreprocessEvent runs before WorldEdit executes the command. FAWE then
        // performs large copies asynchronously, so remember the old clipboard and wait for the
        // one created by this exact command rather than wrapping whatever happens to be present
        // on the following tick.
        final ClipboardHolder previous = clipboard(session);
        final long watch = ++nextClipboardWatch;
        if (schematicLoad) {
            watchClipboard(session, new ClipboardWatch(watch, previous, List.of(), true));
            return;
        }
        final com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());
        final com.sk89q.worldedit.regions.Region selection;
        try {
            selection = session.getSelection(world);
        } catch (final IncompleteRegionException ignored) {
            clipboardWatches.remove(session);
            return;
        }
        final String worldKey = BlockService.worldKey(player.getWorld());
        final List<dev.twme.vanillashape.common.SpecialBlock> snapshot;
        final String sourceMask = sourceMask(event.getMessage());
        try (EditSession maskExtent = sourceMask == null ? null
                : worldEdit.newEditSessionBuilder().world(world).actor(wePlayer).build()) {
            final Mask parsedMask;
            if (sourceMask == null) {
                parsedMask = null;
            } else {
                final ParserContext context = new ParserContext();
                context.setActor(wePlayer);
                context.setWorld(world);
                context.setExtent(maskExtent);
                context.setRestricted(true);
                parsedMask = worldEdit.getMaskFactory().parseFromInput(sourceMask, context);
            }
            snapshot = blocks.inWorld(worldKey).stream()
                    .filter(block -> {
                        final var position = com.sk89q.worldedit.math.BlockVector3.at(
                                block.x(), block.y(), block.z());
                        return selection.contains(position) && (parsedMask == null || parsedMask.test(position));
                    })
                    .toList();
        } catch (final Exception invalidMask) {
            // WorldEdit will report the parser error; do not attach unfiltered virtual blocks.
            clipboardWatches.remove(session);
            return;
        }
        if (snapshot.isEmpty()) {
            clipboardWatches.remove(session);
            return;
        }

        watchClipboard(session, new ClipboardWatch(watch, previous, snapshot, false));
    }

    private void watchClipboard(final LocalSession session, final ClipboardWatch watch) {
        clipboardWatches.put(session, watch);
        patchClipboardWhenReady(session, CLIPBOARD_WAIT_TICKS, watch.id());
    }

    private void patchClipboardWhenReady(final LocalSession session,
                                         final int attemptsLeft,
                                         final long watchId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            final ClipboardWatch watch = clipboardWatches.get(session);
            if (watch == null || watch.id() != watchId) return;
            if (!patchClipboardIfReady(session)) {
                if (attemptsLeft > 1) {
                    patchClipboardWhenReady(session, attemptsLeft - 1, watchId);
                } else {
                    clipboardWatches.remove(session);
                    plugin.getLogger().fine("Timed out waiting for WorldEdit to finish creating its clipboard.");
                }
            }
        });
    }

    /** Patch a clipboard synchronously, including immediately before //paste. */
    private boolean patchClipboardIfReady(final LocalSession session) {
        final ClipboardWatch watch = clipboardWatches.get(session);
        if (watch == null) return false;
        final ClipboardHolder current = clipboard(session);
        if (!newClipboard(watch.previous(), current)) return false;
        final Clipboard original = current.getClipboard();
        if (original instanceof VanillaShapeClipboard) {
            clipboardWatches.remove(session);
            return true;
        }
        final Clipboard wrapped = watch.recoverTiles()
                ? VanillaShapeClipboard.recoverFaweTiles(original, codec)
                : VanillaShapeClipboard.fromWorldSnapshot(original, codec, watch.snapshot());
        if (wrapped != original) {
            // FAWE closes the old holder unless the replacement reports that it retains the
            // original clipboard. Closing here unmaps DiskOptimizedClipboard and makes the
            // wrapper crash on the next //paste, so explicitly transfer ownership.
            final ClipboardHolder replacement = new VanillaShapeClipboardHolder(wrapped, original);
            replacement.setTransform(current.getTransform());
            session.setClipboard(replacement);
            plugin.getLogger().fine("Attached VanillaShape proxy data to the WorldEdit clipboard.");
        }
        clipboardWatches.remove(session);
        return true;
    }

    /** True only after the command replaced the clipboard that existed at preprocess time. */
    static boolean newClipboard(final ClipboardHolder previous, final ClipboardHolder current) {
        if (current == null || current == previous) return false;
        return previous == null || current.getClipboard() != previous.getClipboard();
    }

    /**
     * Route a Fabric-side hit on a virtual block into the tool currently bound
     * by WorldEdit or FAWE. Returns false when no applicable tool is active so
     * VanillaShape can perform its ordinary break/interaction behavior.
     */
    boolean handleToolClick(final Player player, final SpecialBlock target,
                            final PlacementFace clickedFace, final boolean leftClick) {
        if (!player.hasPermission("vanillashape.worldedit")) return false;
        final com.sk89q.worldedit.entity.Player wePlayer = BukkitAdapter.adapt(player);
        final LocalSession session = worldEdit.getSessionManager().get(wePlayer);
        final Tool tool = currentTool(session, wePlayer);
        final boolean toolAllowed = tool != null && tool.canUse(wePlayer);
        final double distanceSquared = distanceSquared(player, target);
        final boolean near = distanceSquared <= NORMAL_INTERACTION_DISTANCE_SQUARED;
        final boolean blockTool = near && toolAllowed && (leftClick
                ? tool instanceof DoubleActionBlockTool : tool instanceof BlockTool);
        final boolean traceTool = distanceSquared <= MAX_TRACE_DISTANCE_SQUARED
                && toolAllowed && (leftClick
                ? tool instanceof DoubleActionTraceTool : tool instanceof TraceTool);
        final BlockTool superPickaxe = near && leftClick && session.hasSuperPickAxe()
                && wePlayer.isHoldingPickAxe() ? session.getSuperPickaxe() : null;
        final boolean superAllowed = superPickaxe != null && superPickaxe.canUse(wePlayer);
        if (!blockTool && !traceTool && !superAllowed) return false;
        final Tool effectiveTool = superAllowed ? superPickaxe : tool;
        if (!leftClick && duplicateToolClick(player, target, clickedFace, effectiveTool)) return true;

        final Direction face = direction(clickedFace);
        final Location location = new Location(BukkitAdapter.adapt(player.getWorld()),
                target.x(), target.y(), target.z());
        if (blockTool || superAllowed) {
            final boolean cancelled = leftClick
                    ? worldEdit.handleBlockLeftClick(wePlayer, location, face)
                    : worldEdit.handleBlockRightClick(wePlayer, location, face);
            if (cancelled) return true;
        }
        if (traceTool) {
            final com.sk89q.worldedit.entity.Player editingPlayer =
                    worldEdit.getPlatformManager().createProxyActor(wePlayer);
            final com.sk89q.worldedit.entity.Player targetedPlayer =
                    VirtualTargetPlayer.wrap(editingPlayer, location, face,
                            Math.sqrt(distanceSquared));
            runTraceTool(wePlayer, targetedPlayer, session, tool, leftClick);
            return true;
        }
        // A recognized block tool may intentionally return false, but there is
        // no real backing block on which vanilla behavior could safely continue.
        return blockTool || superAllowed;
    }

    private static double distanceSquared(final Player player, final SpecialBlock target) {
        final double dx = player.getEyeLocation().getX() - (target.x() + .5);
        final double dy = player.getEyeLocation().getY() - (target.y() + .5);
        final double dz = player.getEyeLocation().getZ() - (target.z() + .5);
        return dx * dx + dy * dy + dz * dz;
    }

    @EventHandler public void onPlayerQuit(final PlayerQuitEvent event) {
        recentToolClicks.remove(event.getPlayer().getUniqueId());
    }

    /** Suppress only duplicate packets for the same tool and virtual target. */
    private boolean duplicateToolClick(final Player player, final SpecialBlock target,
                                       final PlacementFace face, final Tool tool) {
        final long now = System.nanoTime();
        final UUID playerId = player.getUniqueId();
        final RecentToolClick previous = recentToolClicks.put(playerId,
                new RecentToolClick(tool, target.world(), target.x(), target.y(), target.z(), face, now));
        return previous != null && previous.tool() == tool
                && previous.world().equals(target.world())
                && previous.x() == target.x() && previous.y() == target.y()
                && previous.z() == target.z() && previous.face() == face
                && now - previous.atNanos() < SAME_TOOL_TARGET_DEBOUNCE_NANOS;
    }

    private Tool currentTool(final LocalSession session,
                             final com.sk89q.worldedit.entity.Player player) {
        if (fawe) {
            try {
                final Method playerAware = session.getClass().getMethod(
                        "getTool", com.sk89q.worldedit.entity.Player.class);
                final Object result = playerAware.invoke(session, player);
                if (result instanceof Tool tool) return tool;
            } catch (final ReflectiveOperationException error) {
                plugin.getLogger().log(Level.FINE,
                        "FAWE player-aware tool lookup is unavailable; using the held item type", error);
            }
        }
        return session.getTool(player.getItemInHand(HandSide.MAIN_HAND).getType());
    }

    private void runTraceTool(final com.sk89q.worldedit.entity.Player schedulingPlayer,
                              final com.sk89q.worldedit.entity.Player targetedPlayer,
                              final LocalSession session, final Tool tool,
                              final boolean leftClick) {
        final Runnable action = () -> {
            try {
                resetFaweTool(tool);
                final var platform = worldEdit.getPlatformManager()
                        .queryCapability(Capability.WORLD_EDITING);
                final var configuration = worldEdit.getPlatformManager().getConfiguration();
                if (leftClick) {
                    ((DoubleActionTraceTool) tool).actSecondary(
                            platform, configuration, targetedPlayer, session);
                } else {
                    ((TraceTool) tool).actPrimary(
                            platform, configuration, targetedPlayer, session);
                }
            } catch (final Throwable error) {
                handleToolThrowable(error, schedulingPlayer);
            }
        };
        final Runnable contextual = () -> {
            final Request request = Request.request();
            request.setSession(session);
            request.setWorld(targetedPlayer.getWorld());
            try {
                action.run();
            } finally {
                // FAWE 2.15.x uses a ThreadLocal Request, whereas WorldEdit
                // 7.4.x owns it through runWithRequest below.
                if (fawe) Request.reset();
            }
        };
        try {
            if (fawe) {
                final Method runAction = schedulingPlayer.getClass().getMethod(
                        "runAction", Runnable.class, boolean.class, boolean.class);
                runAction.invoke(schedulingPlayer, contextual, false, true);
            } else {
                // Reflective invocation keeps this class loadable on FAWE, whose
                // older Request implementation has no runWithRequest method.
                final Method scoped = Request.class.getMethod("runWithRequest", Runnable.class);
                scoped.invoke(null, contextual);
            }
        } catch (final InvocationTargetException error) {
            handleToolThrowable(error.getCause(), schedulingPlayer);
        } catch (final ReflectiveOperationException error) {
            handleToolThrowable(error, schedulingPlayer);
        }
    }

    /** Match FAWE's own trace-tool dispatch, which resets nested brush patterns first. */
    private void resetFaweTool(final Tool tool) throws ReflectiveOperationException {
        if (!fawe) return;
        final Class<?> traverserType = Class.forName(
                "com.fastasyncworldedit.core.function.pattern.PatternTraverser");
        final Object traverser = traverserType.getConstructor(Object.class).newInstance(tool);
        traverserType.getMethod("reset", com.sk89q.worldedit.extent.Extent.class)
                .invoke(traverser, new Object[] {null});
    }

    private void handleToolThrowable(final Throwable error,
                                     final com.sk89q.worldedit.entity.Player player) {
        if (fawe) {
            try {
                final Method handler = worldEdit.getPlatformManager().getClass().getMethod(
                        "handleThrowable", Throwable.class,
                        com.sk89q.worldedit.extension.platform.Actor.class);
                handler.invoke(worldEdit.getPlatformManager(), error, player);
                return;
            } catch (final ReflectiveOperationException ignored) {
                // Fall through to the portable report below.
            }
        }
        plugin.getLogger().log(Level.WARNING,
                "Could not use a WorldEdit tool on a VanillaShape block", error);
        player.printError("Could not use that WorldEdit tool: " + error.getMessage());
    }

    private static Direction direction(final PlacementFace face) {
        return switch (face) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
        };
    }

    @Override public void close() {
        clipboardWatches.clear();
        recentToolClicks.clear();
        HandlerList.unregisterAll(this);
        worldEdit.getEventBus().unregister(this);
        if (faweAllowedAdded && faweAllowedPlugins != null) faweAllowedPlugins.remove(FAWE_ALLOWED_CLASS);
        if (faweHistorySettings != null && originalCombineStages != null) {
            try {
                final Field field = faweHistorySettings.getClass().getField("COMBINE_STAGES");
                if (!field.getBoolean(faweHistorySettings)) {
                    field.setBoolean(faweHistorySettings, originalCombineStages);
                }
            } catch (final ReflectiveOperationException error) {
                plugin.getLogger().log(Level.FINE, "Could not restore FAWE combine-stages setting", error);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void configureFawe() {
        try {
            final Class<?> settingsClass = Class.forName("com.fastasyncworldedit.core.configuration.Settings");
            final Object settings = settingsClass.getMethod("settings").invoke(null);
            final Object extentSettings = settingsClass.getField("EXTENT").get(settings);
            faweAllowedPlugins = (List<String>) extentSettings.getClass()
                    .getField("ALLOWED_PLUGINS").get(extentSettings);
            if (!faweAllowedPlugins.contains(FAWE_ALLOWED_CLASS)) {
                faweAllowedPlugins.add(FAWE_ALLOWED_CLASS);
                faweAllowedAdded = true;
            }
            faweHistorySettings = settingsClass.getField("HISTORY").get(settings);
            final Field combineStages = faweHistorySettings.getClass().getField("COMBINE_STAGES");
            originalCombineStages = combineStages.getBoolean(faweHistorySettings);
            if (originalCombineStages) {
                combineStages.setBoolean(faweHistorySettings, false);
                plugin.getLogger().info("FAWE combine-stages disabled in memory so VanillaShape proxy states "
                        + "are retained by undo/redo history.");
            }
        } catch (final ReflectiveOperationException | ClassCastException error) {
            throw new IllegalStateException("This FAWE build does not expose the required extent API", error);
        }
    }

    @SuppressWarnings("unchecked")
    private InputParser<Mask> createMaskParser() {
        if (!fawe) {
            return new VanillaShapeMaskParser(worldEdit, blocks, codec,
                    WorldEditIntegration::normalizeMaterial);
        }
        try {
            final Class<?> type = Class.forName(
                    "dev.twme.vanillashape.paper.VanillaShapeFaweMaskParser",
                    true, getClass().getClassLoader());
            final var constructor = type.getDeclaredConstructor(WorldEdit.class, BlockService.class,
                    WorldEditProxyCodec.class, java.util.function.UnaryOperator.class);
            return (InputParser<Mask>) constructor.newInstance(worldEdit, blocks, codec,
                    (java.util.function.UnaryOperator<String>) WorldEditIntegration::normalizeMaterial);
        } catch (final ReflectiveOperationException error) {
            throw new IllegalStateException("Could not initialize the FAWE mask bridge", error);
        }
    }

    private static String normalizeMaterial(final String material) {
        return Bukkit.createBlockData(material).getAsString();
    }

    private static ClipboardHolder clipboard(final LocalSession session) {
        try {
            return session.getClipboard();
        } catch (final EmptyClipboardException ignored) {
            return null;
        }
    }

    private static boolean copyCommand(final String message) {
        final int space = message.indexOf(' ');
        final String label = (space < 0 ? message : message.substring(0, space)).toLowerCase(Locale.ROOT);
        return label.equals("//copy") || label.equals("//cp") || label.equals("//cut")
                || label.equals("//lazycopy") || label.equals("//lazycut")
                || label.equals("/worldedit:/copy") || label.equals("/worldedit:/cut")
                || label.equals("/worldedit:copy") || label.equals("/worldedit:cut")
                || label.equals("/worldedit:/lazycopy") || label.equals("/worldedit:/lazycut")
                || label.equals("/worldedit:lazycopy") || label.equals("/worldedit:lazycut");
    }

    private static boolean pasteCommand(final String message) {
        final int space = message.indexOf(' ');
        final String label = (space < 0 ? message : message.substring(0, space)).toLowerCase(Locale.ROOT);
        return label.equals("//paste") || label.equals("//place")
                || label.equals("/worldedit:/paste") || label.equals("/worldedit:/place")
                || label.equals("/worldedit:paste") || label.equals("/worldedit:place");
    }

    private static boolean schematicLoadCommand(final String message) {
        final String normalized = message.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("//schematic load ") || normalized.equals("//schematic load")
                || normalized.startsWith("//schem load ") || normalized.equals("//schem load")
                || normalized.startsWith("/worldedit:/schematic load ")
                || normalized.startsWith("/worldedit:/schem load ");
    }

    static String sourceMask(final String message) {
        final String padded = " " + message.trim() + " ";
        final int flag = padded.indexOf(" -m ");
        if (flag < 0) return null;
        final int start = flag + 4;
        int end = start;
        while (end < padded.length() && !Character.isWhitespace(padded.charAt(end))) end++;
        return end == start ? "" : padded.substring(start, end);
    }

    private static boolean classPresent(final String name) {
        try {
            Class.forName(name, false, WorldEditIntegration.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        }
    }

    private record ClipboardWatch(long id, ClipboardHolder previous,
                                  List<SpecialBlock> snapshot, boolean recoverTiles) {}
    private record RecentToolClick(Tool tool, String world, int x, int y, int z,
                                   PlacementFace face, long atNanos) {}
}
