package dev.twme.vanillashape.fabric;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class MinecraftCompat {
    private static final MethodHandle LIGHT_LOOKUP = findLightLookup();

    private MinecraftCompat() {}

    static String worldId(final ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    static int light(final ClientLevel level, final BlockPos pos) {
        try {
            return (int) LIGHT_LOOKUP.invokeExact((Object) level, (Object) pos);
        } catch (final Throwable error) {
            return 0x00F000F0;
        }
    }

    private static MethodHandle findLightLookup() {
        for (final String owner : new String[] {
                "net.minecraft.util.LightCoordsUtil",
                "net.minecraft.client.renderer.LevelRenderer"
        }) {
            try {
                for (final Method method : Class.forName(owner).getMethods()) {
                    if (method.getName().equals("getLightCoords")
                            && Modifier.isStatic(method.getModifiers())
                            && method.getParameterCount() == 2
                            && method.getReturnType() == int.class) {
                        return MethodHandles.publicLookup().unreflect(method).asType(
                                MethodType.methodType(int.class, Object.class, Object.class));
                    }
                }
            } catch (final ReflectiveOperationException ignored) {
                // Try the owner used by the other 26.x rendering generation.
            }
        }
        return MethodHandles.dropArguments(MethodHandles.constant(int.class, 0x00F000F0),
                0, Object.class, Object.class);
    }
}
