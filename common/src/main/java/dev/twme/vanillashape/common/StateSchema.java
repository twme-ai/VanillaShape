package dev.twme.vanillashape.common;

import java.util.List;
import java.util.Locale;

/** Shared state schema used by commands, the debug stick and item metadata. */
public final class StateSchema {
    private static final List<String> BOOLEAN = List.of("false", "true");
    private static final List<String> FACING = List.of("north", "east", "south", "west");
    private static final List<String> CORNER = List.of(
            "straight", "inner_left", "inner_right", "outer_left", "outer_right");
    private static final StateProperty WATERLOGGED = property("waterlogged", BOOLEAN);
    private static final StateProperty NORTH = property("north", BOOLEAN);
    private static final StateProperty EAST = property("east", BOOLEAN);
    private static final StateProperty SOUTH = property("south", BOOLEAN);
    private static final StateProperty WEST = property("west", BOOLEAN);
    private static final StateProperty DIRECTION = property("facing", FACING);
    private static final StateProperty HALF = property("half", List.of("bottom", "top"));
    private static final StateProperty OPEN = property("open", BOOLEAN);
    private static final StateProperty POWERED = property("powered", BOOLEAN);
    private static final StateProperty HINGE = property("hinge", List.of("left", "right"));
    private static final StateProperty CORNER_SHAPE = property("corner", CORNER);

    private StateSchema() {}

    public static List<StateProperty> properties(final ShapeType shape) {
        return switch (shape) {
            case WALL, FENCE -> List.of(WATERLOGGED, NORTH, EAST, SOUTH, WEST);
            case FENCE_GATE -> List.of(DIRECTION, OPEN, POWERED, WATERLOGGED);
            case SLAB -> List.of(HALF, WATERLOGGED);
            case STAIRS -> List.of(DIRECTION, HALF, CORNER_SHAPE, WATERLOGGED);
            case DOOR -> List.of(DIRECTION, OPEN, HINGE, POWERED);
            case TRAPDOOR -> List.of(DIRECTION, HALF, OPEN, POWERED, WATERLOGGED);
            case VERTICAL_SLAB -> List.of(DIRECTION, CORNER_SHAPE, WATERLOGGED);
        };
    }

    public static StateProperty property(final ShapeType shape, final String name) {
        final String normalized = normalize(name);
        return properties(shape).stream().filter(value -> value.name().equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Property '" + name + "' does not apply to " + shape.name().toLowerCase(Locale.ROOT)));
    }

    public static String value(final SpecialBlock block, final String property) {
        return switch (normalize(property)) {
            case "facing" -> block.facing().name().toLowerCase(Locale.ROOT);
            case "corner" -> block.corner().name().toLowerCase(Locale.ROOT);
            case "half" -> has(block, SpecialBlock.TOP) ? "top" : "bottom";
            case "hinge" -> has(block, SpecialBlock.HINGE_RIGHT) ? "right" : "left";
            case "waterlogged" -> bool(block, SpecialBlock.WATERLOGGED);
            case "open" -> bool(block, SpecialBlock.OPEN);
            case "powered" -> bool(block, SpecialBlock.POWERED);
            case "north" -> bool(block, SpecialBlock.NORTH);
            case "east" -> bool(block, SpecialBlock.EAST);
            case "south" -> bool(block, SpecialBlock.SOUTH);
            case "west" -> bool(block, SpecialBlock.WEST);
            default -> throw new IllegalArgumentException("Unknown state property: " + property);
        };
    }

    public static SpecialBlock withValue(
            final SpecialBlock block, final String propertyName, final String requestedValue) {
        final StateProperty property = property(block.shape(), propertyName);
        final String value = normalize(requestedValue);
        if (!property.values().contains(value)) {
            throw new IllegalArgumentException(property.name() + " must be one of "
                    + String.join(", ", property.values()));
        }
        return switch (property.name()) {
            case "facing" -> block.withFacing(Direction.valueOf(value.toUpperCase(Locale.ROOT)));
            case "corner" -> block.withCorner(CornerShape.valueOf(value.toUpperCase(Locale.ROOT)));
            case "half" -> withFlag(block, SpecialBlock.TOP, value.equals("top"));
            case "hinge" -> withFlag(block, SpecialBlock.HINGE_RIGHT, value.equals("right"));
            case "waterlogged" -> withFlag(block, SpecialBlock.WATERLOGGED, Boolean.parseBoolean(value));
            case "open" -> withFlag(block, SpecialBlock.OPEN, Boolean.parseBoolean(value));
            case "powered" -> withFlag(block, SpecialBlock.POWERED, Boolean.parseBoolean(value));
            case "north" -> withFlag(block, SpecialBlock.NORTH, Boolean.parseBoolean(value));
            case "east" -> withFlag(block, SpecialBlock.EAST, Boolean.parseBoolean(value));
            case "south" -> withFlag(block, SpecialBlock.SOUTH, Boolean.parseBoolean(value));
            case "west" -> withFlag(block, SpecialBlock.WEST, Boolean.parseBoolean(value));
            default -> throw new IllegalStateException("Unhandled state property " + property.name());
        };
    }

    public static SpecialBlock cycle(
            final SpecialBlock block, final StateProperty property, final boolean reverse) {
        final String current = value(block, property.name());
        final int currentIndex = property.values().indexOf(current);
        final int offset = reverse ? -1 : 1;
        final int nextIndex = Math.floorMod(currentIndex + offset, property.values().size());
        return withValue(block, property.name(), property.values().get(nextIndex));
    }

    private static StateProperty property(final String name, final List<String> values) {
        return new StateProperty(name, values);
    }

    private static String normalize(final String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean has(final SpecialBlock block, final int bit) {
        return (block.flags() & bit) != 0;
    }

    private static String bool(final SpecialBlock block, final int bit) {
        return Boolean.toString(has(block, bit));
    }

    private static SpecialBlock withFlag(final SpecialBlock block, final int bit, final boolean enabled) {
        return block.withFlags(enabled ? block.flags() | bit : block.flags() & ~bit);
    }
}
