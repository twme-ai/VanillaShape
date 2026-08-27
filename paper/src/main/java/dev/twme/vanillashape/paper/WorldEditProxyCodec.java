package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinIntTag;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTagType;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Encodes virtual states as transformable vanilla states plus a private NBT marker. */
final class WorldEditProxyCodec {
    static final String PROXY_ID = "vanillashape:proxy";
    private static final int VERSION = 2;

    BaseBlock encode(final SpecialBlock block) {
        BlockState state = block.shape() == ShapeType.MODEL
                ? modelState(block.model()) : proxyType(block.shape()).getDefaultState();
        final boolean waterlogged = has(block.flags(), SpecialBlock.WATERLOGGED);
        state = switch (block.shape()) {
            case STAIRS -> with(with(with(with(state,
                    "facing", lower(block.facing())), "half", has(block.flags(), SpecialBlock.TOP) ? "top" : "bottom"),
                    "shape", lower(block.corner())), "waterlogged", Boolean.toString(waterlogged));
            case VERTICAL_SLAB -> with(with(with(state, "facing", lower(block.facing())),
                    "shape", lower(block.corner())), "waterlogged", Boolean.toString(waterlogged));
            case SLAB -> with(with(state, "type", has(block.flags(), SpecialBlock.TOP) ? "top" : "bottom"),
                    "waterlogged", Boolean.toString(waterlogged));
            case WALL -> with(with(with(with(with(state,
                    "north", wall(block, SpecialBlock.NORTH)), "east", wall(block, SpecialBlock.EAST)),
                    "south", wall(block, SpecialBlock.SOUTH)), "west", wall(block, SpecialBlock.WEST)),
                    "waterlogged", Boolean.toString(waterlogged));
            case FENCE -> with(with(with(with(with(state,
                    "north", bool(block, SpecialBlock.NORTH)), "east", bool(block, SpecialBlock.EAST)),
                    "south", bool(block, SpecialBlock.SOUTH)), "west", bool(block, SpecialBlock.WEST)),
                    "waterlogged", Boolean.toString(waterlogged));
            case FENCE_GATE -> with(with(with(state, "facing", lower(block.facing())),
                    "open", bool(block, SpecialBlock.OPEN)), "powered", bool(block, SpecialBlock.POWERED));
            case DOOR -> with(with(with(with(with(state, "facing", lower(block.facing())),
                    "half", has(block.flags(), SpecialBlock.DOOR_UPPER) ? "upper" : "lower"),
                    "hinge", has(block.flags(), SpecialBlock.HINGE_RIGHT) ? "right" : "left"),
                    "open", bool(block, SpecialBlock.OPEN)), "powered", bool(block, SpecialBlock.POWERED));
            case TRAPDOOR -> with(with(with(with(with(state, "facing", lower(block.facing())),
                    "half", has(block.flags(), SpecialBlock.TOP) ? "top" : "bottom"),
                    "open", bool(block, SpecialBlock.OPEN)), "powered", bool(block, SpecialBlock.POWERED)),
                    "waterlogged", Boolean.toString(waterlogged));
            case MODEL -> state;
        };
        if (block.shape() == ShapeType.WALL) state = with(state, "up", bool(block, SpecialBlock.WALL_UP));
        final LinCompoundTag marker = LinCompoundTag.builder()
                .putString("id", PROXY_ID)
                .putInt("version", VERSION)
                .putString("shape", lower(block.shape()))
                .putString("material", block.material())
                .putString("model", block.model())
                .putString("facing", lower(block.facing()))
                .putString("corner", lower(block.corner()))
                .putInt("flags", block.flags())
                .build();
        return state.toBaseBlock(marker);
    }

    Optional<SpecialBlock> decode(final BaseBlock proxy, final String world,
                                  final int x, final int y, final int z) {
        final LinCompoundTag marker = proxy.getNbt();
        if (marker == null || !PROXY_ID.equals(string(marker, "id"))
                || integer(marker, "version", -1) < 1
                || integer(marker, "version", -1) > VERSION) return Optional.empty();
        try {
            final ShapeType shape = ShapeType.parse(requiredString(marker, "shape"));
            final String material = requiredString(marker, "material");
            String model = java.util.Objects.requireNonNullElse(string(marker, "model"), "");
            Direction facing = Direction.valueOf(requiredString(marker, "facing").toUpperCase(Locale.ROOT));
            CornerShape corner = CornerShape.valueOf(requiredString(marker, "corner").toUpperCase(Locale.ROOT));
            int flags = integer(marker, "flags", 0) & SpecialBlock.ALL_FLAGS;

            switch (shape) {
                case STAIRS -> {
                    facing = direction(proxy, facing);
                    corner = corner(proxy, corner);
                    flags = flag(flags, SpecialBlock.TOP, property(proxy, "half", "bottom").equals("top"));
                    flags = waterlogged(proxy, flags);
                }
                case VERTICAL_SLAB -> {
                    facing = direction(proxy, facing);
                    corner = corner(proxy, corner);
                    flags = waterlogged(proxy, flags);
                }
                case SLAB -> {
                    flags = flag(flags, SpecialBlock.TOP, property(proxy, "type", "bottom").equals("top"));
                    flags = waterlogged(proxy, flags);
                }
                case WALL -> {
                    flags = connection(proxy, flags, "north", SpecialBlock.NORTH, "none");
                    flags = connection(proxy, flags, "east", SpecialBlock.EAST, "none");
                    flags = connection(proxy, flags, "south", SpecialBlock.SOUTH, "none");
                    flags = connection(proxy, flags, "west", SpecialBlock.WEST, "none");
                    flags = wallTall(proxy, flags, "north", SpecialBlock.WALL_TALL_NORTH);
                    flags = wallTall(proxy, flags, "east", SpecialBlock.WALL_TALL_EAST);
                    flags = wallTall(proxy, flags, "south", SpecialBlock.WALL_TALL_SOUTH);
                    flags = wallTall(proxy, flags, "west", SpecialBlock.WALL_TALL_WEST);
                    flags = flag(flags, SpecialBlock.WALL_UP,
                            boolProperty(proxy, "up", flags, SpecialBlock.WALL_UP));
                    flags = waterlogged(proxy, flags);
                }
                case FENCE -> {
                    flags = connection(proxy, flags, "north", SpecialBlock.NORTH, "false");
                    flags = connection(proxy, flags, "east", SpecialBlock.EAST, "false");
                    flags = connection(proxy, flags, "south", SpecialBlock.SOUTH, "false");
                    flags = connection(proxy, flags, "west", SpecialBlock.WEST, "false");
                    flags = waterlogged(proxy, flags);
                }
                case FENCE_GATE -> {
                    facing = direction(proxy, facing);
                    flags = flag(flags, SpecialBlock.OPEN, boolProperty(proxy, "open", flags, SpecialBlock.OPEN));
                    flags = flag(flags, SpecialBlock.POWERED,
                            boolProperty(proxy, "powered", flags, SpecialBlock.POWERED));
                }
                case DOOR -> {
                    facing = direction(proxy, facing);
                    flags = flag(flags, SpecialBlock.DOOR_UPPER,
                            property(proxy, "half", has(flags, SpecialBlock.DOOR_UPPER) ? "upper" : "lower")
                                    .equals("upper"));
                    flags = flag(flags, SpecialBlock.HINGE_RIGHT,
                            property(proxy, "hinge", has(flags, SpecialBlock.HINGE_RIGHT) ? "right" : "left")
                                    .equals("right"));
                    flags = flag(flags, SpecialBlock.OPEN, boolProperty(proxy, "open", flags, SpecialBlock.OPEN));
                    flags = flag(flags, SpecialBlock.POWERED,
                            boolProperty(proxy, "powered", flags, SpecialBlock.POWERED));
                }
                case TRAPDOOR -> {
                    facing = direction(proxy, facing);
                    flags = flag(flags, SpecialBlock.TOP,
                            property(proxy, "half", has(flags, SpecialBlock.TOP) ? "top" : "bottom").equals("top"));
                    flags = flag(flags, SpecialBlock.OPEN, boolProperty(proxy, "open", flags, SpecialBlock.OPEN));
                    flags = flag(flags, SpecialBlock.POWERED,
                            boolProperty(proxy, "powered", flags, SpecialBlock.POWERED));
                    flags = waterlogged(proxy, flags);
                }
                // BaseBlock#getAsString also appends our marker SNBT; only the transformed
                // immutable carrier state belongs in the model BlockData field.
                case MODEL -> model = proxy.toImmutableState().getAsString();
            }
            return Optional.of(new SpecialBlock(world, x, y, z, shape, material, model,
                    facing, corner, flags));
        } catch (final RuntimeException invalid) {
            return Optional.empty();
        }
    }

    boolean isProxy(final BaseBlock block) {
        final LinCompoundTag marker = block.getNbt();
        return marker != null && PROXY_ID.equals(string(marker, "id"));
    }

    boolean isCarrier(final BlockStateHolder<?> block, final SpecialBlock expected) {
        if (expected.shape() == ShapeType.MODEL) {
            final int bracket = expected.model().indexOf('[');
            final String id = bracket < 0 ? expected.model() : expected.model().substring(0, bracket);
            return block.getBlockType().id().equals(id);
        }
        return block.getBlockType().equals(proxyType(expected.shape()));
    }

    private static BlockType proxyType(final ShapeType shape) {
        final String id = switch (shape) {
            case STAIRS, VERTICAL_SLAB -> "minecraft:oak_stairs";
            case SLAB -> "minecraft:oak_slab";
            case WALL -> "minecraft:cobblestone_wall";
            case FENCE -> "minecraft:oak_fence";
            case FENCE_GATE -> "minecraft:oak_fence_gate";
            case DOOR -> "minecraft:oak_door";
            case TRAPDOOR -> "minecraft:oak_trapdoor";
            case MODEL -> throw new IllegalArgumentException("Model proxies use their own BlockData type");
        };
        return Objects.requireNonNull(BlockTypes.get(id), "WorldEdit is missing proxy type " + id);
    }

    private static BlockState modelState(final String blockData) {
        if (blockData == null || blockData.isBlank()) {
            throw new IllegalArgumentException("vanillashape:model requires a model BlockData value");
        }
        final int bracket = blockData.indexOf('[');
        final String id = bracket < 0 ? blockData : blockData.substring(0, bracket);
        final BlockType type = Objects.requireNonNull(BlockTypes.get(id), "Unknown model block type " + id);
        BlockState state = type.getDefaultState();
        if (bracket >= 0 && blockData.endsWith("]")) {
            for (final String assignment : blockData.substring(bracket + 1, blockData.length() - 1).split(",")) {
                final int equals = assignment.indexOf('=');
                if (equals < 1) continue;
                state = with(state, assignment.substring(0, equals).trim(),
                        assignment.substring(equals + 1).trim());
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static BlockState with(final BlockState state, final String name, final String value) {
        final Property<Object> property = (Property<Object>) state.getBlockType().getPropertyMap().get(name);
        if (property == null) throw new IllegalStateException(state.getBlockType().id() + " has no " + name);
        return state.with(property, property.getValueFor(value));
    }

    @SuppressWarnings("unchecked")
    private static String property(final BlockStateHolder<?> state, final String name, final String fallback) {
        final Property<Object> property = (Property<Object>) state.getBlockType().getPropertyMap().get(name);
        if (property == null) return fallback;
        final Object value = state.getState(property);
        return value == null ? fallback : value.toString().toLowerCase(Locale.ROOT);
    }

    private static Direction direction(final BlockStateHolder<?> state, final Direction fallback) {
        return Direction.valueOf(property(state, "facing", lower(fallback)).toUpperCase(Locale.ROOT));
    }

    private static CornerShape corner(final BlockStateHolder<?> state, final CornerShape fallback) {
        return CornerShape.valueOf(property(state, "shape", lower(fallback)).toUpperCase(Locale.ROOT));
    }

    private static int waterlogged(final BlockStateHolder<?> state, final int flags) {
        return flag(flags, SpecialBlock.WATERLOGGED,
                boolProperty(state, "waterlogged", flags, SpecialBlock.WATERLOGGED));
    }

    private static boolean boolProperty(final BlockStateHolder<?> state, final String name,
                                        final int flags, final int bit) {
        return Boolean.parseBoolean(property(state, name, bool(flags, bit)));
    }

    private static int connection(final BlockStateHolder<?> state, final int flags,
                                  final String name, final int bit, final String disconnected) {
        return flag(flags, bit, !property(state, name, has(flags, bit) ? "true" : disconnected)
                .equals(disconnected));
    }

    private static int flag(final int flags, final int bit, final boolean enabled) {
        return enabled ? flags | bit : flags & ~bit;
    }

    private static String wall(final SpecialBlock block, final int bit) {
        if (!has(block.flags(), bit)) return "none";
        final int tall = switch (bit) {
            case SpecialBlock.NORTH -> SpecialBlock.WALL_TALL_NORTH;
            case SpecialBlock.EAST -> SpecialBlock.WALL_TALL_EAST;
            case SpecialBlock.SOUTH -> SpecialBlock.WALL_TALL_SOUTH;
            case SpecialBlock.WEST -> SpecialBlock.WALL_TALL_WEST;
            default -> 0;
        };
        return has(block.flags(), tall) ? "tall" : "low";
    }

    private static int wallTall(final BlockStateHolder<?> state, final int flags,
                                final String name, final int bit) {
        return flag(flags, bit, property(state, name, "none").equals("tall"));
    }

    private static String bool(final SpecialBlock block, final int bit) {
        return bool(block.flags(), bit);
    }

    private static String bool(final int flags, final int bit) {
        return Boolean.toString(has(flags, bit));
    }

    private static boolean has(final int flags, final int bit) {
        return (flags & bit) != 0;
    }

    private static String lower(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String requiredString(final LinCompoundTag tag, final String key) {
        final String value = string(tag, key);
        if (value == null) throw new IllegalArgumentException("Missing proxy field " + key);
        return value;
    }

    private static String string(final LinCompoundTag tag, final String key) {
        final LinStringTag value = tag.findTag(key, LinTagType.stringTag());
        return value == null ? null : value.value();
    }

    private static int integer(final LinCompoundTag tag, final String key, final int fallback) {
        final LinIntTag value = tag.findTag(key, LinTagType.intTag());
        return value == null ? fallback : value.value();
    }
}
