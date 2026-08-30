package dev.twme.vanillashape.fabric;

import net.minecraft.client.multiplayer.ClientLevel;

final class MinecraftCompat {
    private MinecraftCompat() {}

    static String worldId(final ClientLevel level) {
        return level.dimension().identifier().toString();
    }
}
