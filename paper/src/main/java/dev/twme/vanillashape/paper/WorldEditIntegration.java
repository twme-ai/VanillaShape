package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.internal.registry.InputParser;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/** Optional WorldEdit/FAWE bridge. This class is only loaded when either plugin is present. */
final class WorldEditIntegration implements Listener, AutoCloseable {
    private static final String FAWE_ALLOWED_CLASS = VanillaShapeExtent.class.getName();
    private final VanillaShapePlugin plugin;
    private final BlockService blocks;
    private final WorldEdit worldEdit;
    private final WorldEditProxyCodec codec = new WorldEditProxyCodec();
    private final boolean fawe;
    private List<String> faweAllowedPlugins;
    private boolean faweAllowedAdded;
    private Object faweHistorySettings;
    private Boolean originalCombineStages;

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

    /** WE and FAWE bypass injected extents for clipboard reads; patch the command clipboard after creation. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldEditCopy(final PlayerCommandPreprocessEvent event) {
        final boolean copy = copyCommand(event.getMessage());
        final boolean schematicLoad = schematicLoadCommand(event.getMessage());
        if (!copy && !schematicLoad) return;
        final var player = event.getPlayer();
        if (!player.hasPermission("vanillashape.worldedit")) return;
        final var wePlayer = BukkitAdapter.adapt(player);
        final LocalSession session = worldEdit.getSessionManager().get(wePlayer);
        if (schematicLoad) {
            patchClipboardWhenReady(session, List.of(), 40);
            return;
        }
        final com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());
        final com.sk89q.worldedit.regions.Region selection;
        try {
            selection = session.getSelection(world);
        } catch (final IncompleteRegionException ignored) {
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
            return;
        }
        if (snapshot.isEmpty()) return;

        patchClipboardWhenReady(session, snapshot, 40);
    }

    private void patchClipboardWhenReady(final LocalSession session,
                                         final List<dev.twme.vanillashape.common.SpecialBlock> snapshot,
                                         final int attemptsLeft) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            final ClipboardHolder current = clipboard(session);
            // FAWE can mutate its existing ClipboardHolder rather than replacing it. Waiting for
            // object identity to change silently skips proxy wrapping, so only wait for no holder.
            if (current == null) {
                if (attemptsLeft > 1) patchClipboardWhenReady(session, snapshot, attemptsLeft - 1);
                return;
            }
            final Clipboard original = current.getClipboard();
            final Clipboard wrapped = snapshot.isEmpty()
                    ? VanillaShapeClipboard.recoverFaweTiles(original, codec)
                    : VanillaShapeClipboard.fromWorldSnapshot(original, codec, snapshot);
            if (wrapped == original) return;
            // FAWE closes the old holder unless the replacement reports that it retains the
            // original clipboard. Closing here unmaps DiskOptimizedClipboard and makes the
            // wrapper crash on the next //paste, so explicitly transfer ownership.
            final ClipboardHolder replacement = new VanillaShapeClipboardHolder(wrapped, original);
            replacement.setTransform(current.getTransform());
            session.setClipboard(replacement);
            plugin.getLogger().fine("Attached VanillaShape proxy data to the WorldEdit clipboard.");
        });
    }

    @Override public void close() {
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
                || label.equals("/worldedit:/copy") || label.equals("/worldedit:/cut")
                || label.equals("/worldedit:copy") || label.equals("/worldedit:cut");
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
}
