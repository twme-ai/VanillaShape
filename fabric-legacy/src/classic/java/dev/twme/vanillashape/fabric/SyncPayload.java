package dev.twme.vanillashape.fabric;

import dev.twme.vanillashape.common.WireProtocol;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

record SyncPayload(byte[] data) implements CustomPacketPayload {
    static final Type<SyncPayload> TYPE = new Type<>(ResourceLocation.parse(WireProtocol.CHANNEL));
    static final StreamCodec<ByteBuf, SyncPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data),
            buffer -> {
                final byte[] data = new byte[buffer.readableBytes()];
                buffer.readBytes(data);
                return new SyncPayload(data);
            });

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
