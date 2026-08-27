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
    public static final int VERSION = 2;
    public static final byte HELLO = 1;
    public static final byte RESET = 2;
    public static final byte UPSERT = 3;
    public static final byte REMOVE = 4;
    public static final byte DEBUG_SELECT = 5;
    public static final byte DEBUG_CYCLE = 6;
    public static final byte PLACE_ITEM = 7;
    public static final byte PICK_ITEM = 8;
    public static final byte AXIOM_PLACE = 9;
    public static final byte AXIOM_REPLACE = 10;
    public static final byte AXIOM_DELETE = 11;

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
    public static byte[] debugSelect(final int x, final int y, final int z, final boolean reverse) {
        return coordinate(DEBUG_SELECT, x, y, z, reverse);
    }
    public static byte[] debugCycle(final int x, final int y, final int z, final boolean reverse) {
        return coordinate(DEBUG_CYCLE, x, y, z, reverse);
    }
    public static byte[] placeItem(final int x, final int y, final int z) {
        return coordinate(PLACE_ITEM, x, y, z, false);
    }
    public static byte[] pickItem(final int x, final int y, final int z) {
        return coordinate(PICK_ITEM, x, y, z, false);
    }
    public static byte[] axiomPlace(final int x, final int y, final int z) {
        return coordinate(AXIOM_PLACE, x, y, z, false);
    }
    public static byte[] axiomReplace(final int x, final int y, final int z) {
        return coordinate(AXIOM_REPLACE, x, y, z, false);
    }
    public static byte[] axiomDelete(final int x, final int y, final int z) {
        return coordinate(AXIOM_DELETE, x, y, z, false);
    }

    public static Decoded decode(final byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            final int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported protocol version " + version);
            final byte action = in.readByte();
            final Decoded decoded = switch (action) {
                case HELLO -> new Decoded(action, null, null, 0, 0, 0, false);
                case RESET -> new Decoded(action, readString(in), null, 0, 0, 0, false);
                case UPSERT -> {
                    final SpecialBlock block = readBlock(in);
                    yield new Decoded(action, block.world(), block, block.x(), block.y(), block.z(), false);
                }
                case REMOVE -> new Decoded(action, readString(in), null,
                        in.readInt(), in.readInt(), in.readInt(), false);
                case DEBUG_SELECT, DEBUG_CYCLE, PLACE_ITEM, PICK_ITEM,
                        AXIOM_PLACE, AXIOM_REPLACE, AXIOM_DELETE -> new Decoded(
                        action, null, null, in.readInt(), in.readInt(), in.readInt(), in.readBoolean());
                default -> throw new IOException("Unknown action " + action);
            };
            if (in.available() != 0) throw new IOException("Trailing bytes after action " + action);
            return decoded;
        }
    }

    private static byte[] coordinate(
            final byte action, final int x, final int y, final int z, final boolean reverse) {
        return packet(action, out -> {
            out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeBoolean(reverse);
        });
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
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }

    public record Decoded(
            byte action, String world, SpecialBlock block, int x, int y, int z, boolean reverse) {}
}
