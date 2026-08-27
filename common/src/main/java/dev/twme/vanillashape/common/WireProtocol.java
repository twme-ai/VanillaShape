package dev.twme.vanillashape.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned Paper plugin-message payload shared with the Fabric client. */
public final class WireProtocol {
    public static final String CHANNEL = "vanillashape:sync";
    public static final int VERSION = 1;
    public static final byte HELLO = 1;
    public static final byte RESET = 2;
    public static final byte UPSERT = 3;
    public static final byte REMOVE = 4;

    private WireProtocol() {}

    public static byte[] hello() { return packet(HELLO, null); }
    public static byte[] reset(final String world) { return packet(RESET, out -> writeString(out, world)); }
    public static byte[] upsert(final SpecialBlock block) { return packet(UPSERT, out -> writeBlock(out, block)); }
    public static byte[] remove(final String world, final int x, final int y, final int z) {
        return packet(REMOVE, out -> {
            writeString(out, world);
            out.writeInt(x); out.writeInt(y); out.writeInt(z);
        });
    }

    public static Decoded decode(final byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            final int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported protocol version " + version);
            final byte action = in.readByte();
            return switch (action) {
                case HELLO -> new Decoded(action, null, null, 0, 0, 0);
                case RESET -> new Decoded(action, readString(in), null, 0, 0, 0);
                case UPSERT -> {
                    final SpecialBlock block = readBlock(in);
                    yield new Decoded(action, block.world(), block, block.x(), block.y(), block.z());
                }
                case REMOVE -> new Decoded(action, readString(in), null,
                        in.readInt(), in.readInt(), in.readInt());
                default -> throw new IOException("Unknown action " + action);
            };
        }
    }

    private static byte[] packet(final byte action, final Writer writer) {
        try {
            final var bytes = new ByteArrayOutputStream(256);
            try (var out = new DataOutputStream(bytes)) {
                out.writeByte(VERSION);
                out.writeByte(action);
                if (writer != null) writer.write(out);
            }
            return bytes.toByteArray();
        } catch (final IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeBlock(final DataOutputStream out, final SpecialBlock block) throws IOException {
        writeString(out, block.world());
        out.writeInt(block.x()); out.writeInt(block.y()); out.writeInt(block.z());
        out.writeByte(block.shape().ordinal());
        writeString(out, block.material());
        out.writeByte(block.facing().ordinal());
        out.writeByte(block.corner().ordinal());
        out.writeInt(block.flags());
    }

    private static SpecialBlock readBlock(final DataInputStream in) throws IOException {
        final String world = readString(in);
        final int x = in.readInt(), y = in.readInt(), z = in.readInt();
        final ShapeType shape = enumAt(ShapeType.values(), in.readUnsignedByte(), "shape");
        final String material = readString(in);
        final Direction facing = enumAt(Direction.values(), in.readUnsignedByte(), "facing");
        final CornerShape corner = enumAt(CornerShape.values(), in.readUnsignedByte(), "corner");
        return new SpecialBlock(world, x, y, z, shape, material, facing, corner, in.readInt());
    }

    private static <T> T enumAt(final T[] values, final int index, final String name) throws IOException {
        if (index < 0 || index >= values.length) throw new IOException("Invalid " + name + " " + index);
        return values[index];
    }

    private static void writeString(final DataOutputStream out, final String value) throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32767) throw new IOException("String is too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(final DataInputStream in) throws IOException {
        final int length = in.readUnsignedShort();
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }

    public record Decoded(byte action, String world, SpecialBlock block, int x, int y, int z) {}
}
