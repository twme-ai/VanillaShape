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
    public static final int VERSION = 6;
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
    public static final byte BREAK_BLOCK = 12;
    public static final byte INTERACT_BLOCK = 13;
    public static final byte AXIOM_COPY = 14;
    public static final byte AXIOM_PASTE = 15;

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
    public static byte[] placeItem(final int x, final int y, final int z,
                                   final PlacementFace face,
                                   final float hitX, final float hitY, final float hitZ) {
        return placement(PLACE_ITEM, x, y, z, face, hitX, hitY, hitZ);
    }
    public static byte[] pickItem(final int x, final int y, final int z) {
        return coordinate(PICK_ITEM, x, y, z, false);
    }
    public static byte[] axiomPlace(final int x, final int y, final int z,
                                   final PlacementFace face,
                                   final float hitX, final float hitY, final float hitZ) {
        return placement(AXIOM_PLACE, x, y, z, face, hitX, hitY, hitZ);
    }
    public static byte[] axiomReplace(final int x, final int y, final int z) {
        return coordinate(AXIOM_REPLACE, x, y, z, false);
    }
    public static byte[] axiomDelete(final int x, final int y, final int z) {
        return coordinate(AXIOM_DELETE, x, y, z, false);
    }
    public static byte[] breakBlock(final int x, final int y, final int z) {
        return coordinate(BREAK_BLOCK, x, y, z, false);
    }
    public static byte[] interactBlock(final int x, final int y, final int z) {
        return coordinate(INTERACT_BLOCK, x, y, z, false);
    }
    public static byte[] axiomCopy(final int minX, final int minY, final int minZ,
                                   final int maxX, final int maxY, final int maxZ) {
        return packet(AXIOM_COPY, out -> {
            out.writeInt(minX); out.writeInt(minY); out.writeInt(minZ);
            out.writeInt(maxX); out.writeInt(maxY); out.writeInt(maxZ);
        });
    }
    public static byte[] axiomPaste(final int x, final int y, final int z) {
        return coordinate(AXIOM_PASTE, x, y, z, false);
    }

    public static Decoded decode(final byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            final int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported protocol version " + version);
            final byte action = in.readByte();
            final Decoded decoded = switch (action) {
                case HELLO -> empty(action, null);
                case RESET -> empty(action, readString(in));
                case UPSERT -> {
                    final SpecialBlock block = readBlock(in);
                    yield new Decoded(action, block.world(), block, block.x(), block.y(), block.z(),
                            false, null, 0, 0, 0, 0, 0, 0);
                }
                case REMOVE -> new Decoded(action, readString(in), null,
                        in.readInt(), in.readInt(), in.readInt(), false, null, 0, 0, 0,
                        0, 0, 0);
                case DEBUG_SELECT, DEBUG_CYCLE, PICK_ITEM, BREAK_BLOCK, INTERACT_BLOCK,
                        AXIOM_REPLACE, AXIOM_DELETE, AXIOM_PASTE -> new Decoded(
                        action, null, null, in.readInt(), in.readInt(), in.readInt(), in.readBoolean(),
                        null, 0, 0, 0, 0, 0, 0);
                case PLACE_ITEM, AXIOM_PLACE -> readPlacement(action, in);
                case AXIOM_COPY -> new Decoded(action, null, null,
                        in.readInt(), in.readInt(), in.readInt(), false, null, 0, 0, 0,
                        in.readInt(), in.readInt(), in.readInt());
                default -> throw new IOException("Unknown action " + action);
            };
            if (in.available() != 0) throw new IOException("Trailing bytes after action " + action);
            return decoded;
        }
    }

    private static Decoded empty(final byte action, final String world) {
        return new Decoded(action, world, null, 0, 0, 0, false, null, 0, 0, 0,
                0, 0, 0);
    }

    private static byte[] coordinate(
            final byte action, final int x, final int y, final int z, final boolean reverse) {
        return packet(action, out -> {
            out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeBoolean(reverse);
        });
    }

    private static byte[] placement(
            final byte action, final int x, final int y, final int z,
            final PlacementFace face, final float hitX, final float hitY, final float hitZ) {
        if (face == null) throw new IllegalArgumentException("Placement face is required");
        validateHit(hitX, hitY, hitZ);
        return packet(action, out -> {
            out.writeInt(x); out.writeInt(y); out.writeInt(z);
            out.writeByte(face.ordinal());
            out.writeFloat(hitX); out.writeFloat(hitY); out.writeFloat(hitZ);
        });
    }

    private static Decoded readPlacement(final byte action, final DataInputStream in) throws IOException {
        final int x = in.readInt(), y = in.readInt(), z = in.readInt();
        final PlacementFace face = enumAt(PlacementFace.values(), in.readUnsignedByte(), "placement face");
        final float hitX = in.readFloat(), hitY = in.readFloat(), hitZ = in.readFloat();
        try {
            validateHit(hitX, hitY, hitZ);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
        return new Decoded(action, null, null, x, y, z, false, face, hitX, hitY, hitZ,
                0, 0, 0);
    }

    private static void validateHit(final float hitX, final float hitY, final float hitZ) {
        if (!validUnit(hitX) || !validUnit(hitY) || !validUnit(hitZ)) {
            throw new IllegalArgumentException("Placement hit coordinates must be finite values from 0 to 1");
        }
    }

    private static boolean validUnit(final float value) {
        return Float.isFinite(value) && value >= 0 && value <= 1;
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
        writeString(out, block.model());
        out.writeByte(block.facing().ordinal());
        out.writeByte(block.corner().ordinal());
        out.writeInt(block.flags());
    }

    private static SpecialBlock readBlock(final DataInputStream in) throws IOException {
        final String world = readString(in);
        final int x = in.readInt(), y = in.readInt(), z = in.readInt();
        final ShapeType shape = enumAt(ShapeType.values(), in.readUnsignedByte(), "shape");
        final String material = readString(in);
        final String model = readString(in);
        final Direction facing = enumAt(Direction.values(), in.readUnsignedByte(), "facing");
        final CornerShape corner = enumAt(CornerShape.values(), in.readUnsignedByte(), "corner");
        return new SpecialBlock(world, x, y, z, shape, material, model, facing, corner, in.readInt());
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
            byte action, String world, SpecialBlock block, int x, int y, int z, boolean reverse,
            PlacementFace face, float hitX, float hitY, float hitZ, int x2, int y2, int z2) {}
}
