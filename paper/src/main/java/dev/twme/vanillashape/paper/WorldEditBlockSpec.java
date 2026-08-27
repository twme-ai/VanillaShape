package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.CornerShape;
import dev.twme.vanillashape.common.Direction;
import dev.twme.vanillashape.common.ShapeType;
import dev.twme.vanillashape.common.SpecialBlock;
import dev.twme.vanillashape.common.StateProperty;
import dev.twme.vanillashape.common.StateSchema;
import org.enginehub.linbus.format.snbt.LinStringIO;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinTag;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Parsed WorldEdit block-list entry and optional exact matching fields. */
record WorldEditBlockSpec(SpecialBlock template, boolean materialSpecified, boolean modelSpecified,
                          boolean flagsSpecified, Set<String> stateProperties) {
    static final String PREFIX = "vanillashape:";

    WorldEditBlockSpec {
        stateProperties = Set.copyOf(stateProperties);
    }

    static WorldEditBlockSpec parse(final String original, final UnaryOperator<String> materialNormalizer) {
        String input = original.trim();
        if (input.startsWith("=")) input = input.substring(1);
        final int wrappedState = input.indexOf("[{");
        if (wrappedState >= 0 && input.endsWith("}]")) {
            input = input.substring(0, wrappedState) + input.substring(wrappedState + 1, input.length() - 1);
        }
        if (!input.toLowerCase(Locale.ROOT).startsWith(PREFIX)) return null;

        final int nbtStart = input.indexOf('{');
        final String id = (nbtStart < 0 ? input : input.substring(0, nbtStart)).toLowerCase(Locale.ROOT);
        final ShapeType shape;
        try {
            shape = ShapeType.parse(id.substring(PREFIX.length()));
        } catch (final RuntimeException invalid) {
            throw new IllegalArgumentException("Unknown VanillaShape block: " + id, invalid);
        }
        if (nbtStart < 0 && !id.equals(input.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unexpected text after " + id);
        }

        String material = "minecraft:stone";
        int flags = 0;
        boolean materialSpecified = false;
        boolean modelSpecified = false;
        boolean flagsSpecified = false;
        final Set<String> properties = new LinkedHashSet<>();
        SpecialBlock template = new SpecialBlock("minecraft:overworld", 0, 0, 0,
                shape, material, shape == ShapeType.MODEL ? "minecraft:stone" : "",
                Direction.NORTH, CornerShape.STRAIGHT, flags);

        if (nbtStart >= 0) {
            if (!input.endsWith("}")) throw new IllegalArgumentException("Missing closing '}' in " + input);
            final LinCompoundTag data;
            try {
                data = LinStringIO.readFromStringUsing(input.substring(nbtStart), LinCompoundTag::readFrom);
            } catch (final Exception invalid) {
                throw new IllegalArgumentException("Invalid VanillaShape state NBT: " + invalid.getMessage(), invalid);
            }
            for (final var entry : data.value().entrySet()) {
                final String name = entry.getKey().toLowerCase(Locale.ROOT).replace('-', '_');
                final LinTag<?> tag = entry.getValue();
                switch (name) {
                    case "material" -> {
                        if (!(tag.value() instanceof String value)) {
                            throw new IllegalArgumentException("material must be an SNBT string");
                        }
                        material = materialNormalizer.apply(value);
                        materialSpecified = true;
                        template = template.withMaterial(material);
                    }
                    case "model" -> {
                        if (!(tag.value() instanceof String value)) {
                            throw new IllegalArgumentException("model must be an SNBT string");
                        }
                        template = template.withModel(materialNormalizer.apply(value));
                        modelSpecified = true;
                    }
                    case "flags" -> {
                        if (!(tag.value() instanceof Number value)) {
                            throw new IllegalArgumentException("flags must be an integer");
                        }
                        flags = value.intValue();
                        if ((flags & ~SpecialBlock.ALL_FLAGS) != 0) {
                            throw new IllegalArgumentException("flags contains unknown bits: " + flags);
                        }
                        flagsSpecified = true;
                        template = template.withFlags(flags);
                    }
                    default -> {
                        final StateProperty property = StateSchema.property(shape, name);
                        String value = String.valueOf(tag.value()).toLowerCase(Locale.ROOT);
                        if (property.values().equals(java.util.List.of("false", "true"))
                                && tag.value() instanceof Number number) {
                            value = number.intValue() == 0 ? "false" : "true";
                        }
                        template = StateSchema.withValue(template, name, value);
                        properties.add(name);
                    }
                }
            }
        }
        return new WorldEditBlockSpec(template, materialSpecified, modelSpecified, flagsSpecified, properties);
    }

    boolean matches(final SpecialBlock block) {
        if (block.shape() != template.shape()) return false;
        if (modelSpecified && !block.model().equals(template.model())) return false;
        if (materialSpecified && !block.material().equals(template.material())) return false;
        if (flagsSpecified && block.flags() != template.flags()) return false;
        for (final String property : stateProperties) {
            if (!StateSchema.value(block, property).equals(StateSchema.value(template, property))) return false;
        }
        return true;
    }
}
