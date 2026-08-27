package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.WireProtocol;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.file.Files;
import java.util.Objects;
import java.util.logging.Level;

public final class VanillaShapePlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private BlockRepository repository;
    private BlockService blocks;

    @Override public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            repository = new BlockRepository(getDataFolder().toPath().resolve("blocks.db"));
            blocks = new BlockService(this, repository);
        } catch (final Exception error) {
            getLogger().log(Level.SEVERE, "Could not open VanillaShape block database", error);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getMessenger().registerOutgoingPluginChannel(this, WireProtocol.CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, WireProtocol.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        final ShapeCommand executor = new ShapeCommand(blocks);
        final PluginCommand command = Objects.requireNonNull(getCommand("vshape"));
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getLogger().info("Loaded " + blocks.inWorld("minecraft:overworld").size()
                + " overworld special blocks; Fabric clients may now synchronize.");
    }

    @Override public void onDisable() {
        if (repository != null) repository.close();
    }

    @Override public void onPluginMessageReceived(
            final String channel, final Player player, final byte[] message) {
        if (!WireProtocol.CHANNEL.equals(channel) || message.length < 2) return;
        try {
            if (WireProtocol.decode(message).action() == WireProtocol.HELLO) blocks.sync(player);
        } catch (final Exception error) {
            getLogger().log(Level.WARNING, "Rejected malformed VanillaShape hello from " + player.getName(), error);
        }
    }

    @EventHandler public void onJoin(final PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> blocks.sync(event.getPlayer()), 20L);
    }

    @EventHandler public void onWorldChange(final PlayerChangedWorldEvent event) {
        blocks.sync(event.getPlayer());
    }
}
