package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.Location;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class VirtualTargetPlayerTest {
    @Test void substitutesAllTraceVariantsAndDelegatesOtherPlayerBehavior() {
        final BlockVector3 point = BlockVector3.at(12, 80, -5);
        final var extent = new BlockArrayClipboard(new CuboidRegion(point, point));
        final Location fallback = new Location(extent, point.add(2, 0, 0).toVector3());
        final Player delegate = (Player) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Builder";
                    case "getBlockTrace", "getSolidBlockTrace", "getBlockTraceFace" -> fallback;
                    default -> defaultValue(method.getReturnType());
                });
        final Location target = new Location(extent, point.toVector3());
        final Player wrapped = VirtualTargetPlayer.wrap(delegate, target, Direction.WEST, 12);

        assertEquals("Builder", wrapped.getName());
        assertSame(target, wrapped.getBlockTrace(20));
        assertSame(target, wrapped.getBlockTrace(20, true));
        assertSame(target, wrapped.getBlockTrace(20, false, position -> true));
        assertSame(target, wrapped.getSolidBlockTrace(20));
        assertEquals(point.add(-1, 0, 0),
                wrapped.getBlockTraceFace(20, false).toVector().toBlockPoint());
        assertSame(fallback, wrapped.getBlockTrace(10));
        assertSame(fallback, wrapped.getSolidBlockTrace(10));
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        throw new AssertionError(type);
    }
}
