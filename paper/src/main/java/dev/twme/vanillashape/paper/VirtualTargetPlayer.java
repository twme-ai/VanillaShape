package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.Location;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * A one-shot WorldEdit player view whose trace ends at a Fabric-rendered block.
 *
 * <p>VanillaShape blocks are air in the backing world, so WorldEdit trace tools
 * cannot discover them by tracing the Bukkit world. All player/session behavior
 * still delegates to WorldEdit's Bukkit player; only the already validated
 * client ray-trace result is substituted.</p>
 */
final class VirtualTargetPlayer {
    private VirtualTargetPlayer() {}

    static com.sk89q.worldedit.entity.Player wrap(
            final com.sk89q.worldedit.entity.Player delegate,
            final Location target, final Direction face, final double targetDistance) {
        final Location targetFace = new Location(
                target.getExtent(), target.toVector().add(face.toVector()));
        return (com.sk89q.worldedit.entity.Player) Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                new Class<?>[] {com.sk89q.worldedit.entity.Player.class},
                (proxy, method, args) -> {
                    final String name = method.getName();
                    if (name.equals("getBlockTrace") || name.equals("getSolidBlockTrace")) {
                        if (withinRange(args, targetDistance)) return target;
                    }
                    if (name.equals("getBlockTraceFace")) {
                        if (withinRange(args, targetDistance)) return targetFace;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (final InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private static boolean withinRange(final Object[] args, final double targetDistance) {
        if (args == null || args.length == 0 || !(args[0] instanceof Number range)) return true;
        // WorldEdit traces block cells rather than their centers, so allow one
        // block of geometric tolerance at the configured boundary.
        return targetDistance <= range.doubleValue() + 1;
    }
}
