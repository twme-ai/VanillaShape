package dev.twme.vanillashape.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateSchemaTest {
    private static SpecialBlock stair() {
        return new SpecialBlock("minecraft:overworld", 1, 2, 3, ShapeType.STAIRS,
                "minecraft:stone", Direction.NORTH, CornerShape.STRAIGHT, 0);
    }

    @Test void cyclesFacingInDebugStickOrder() {
        final SpecialBlock changed = StateSchema.cycle(stair(), StateSchema.property(ShapeType.STAIRS, "facing"), false);
        assertEquals(Direction.EAST, changed.facing());
        assertEquals("east", StateSchema.value(changed, "facing"));
    }

    @Test void supportsShapeSpecificBooleanAndEnumStates() {
        final SpecialBlock top = StateSchema.withValue(stair(), "half", "top");
        final SpecialBlock inner = StateSchema.withValue(top, "corner", "inner-left");
        assertEquals("top", StateSchema.value(inner, "half"));
        assertEquals(CornerShape.INNER_LEFT, inner.corner());
    }

    @Test void rejectsStatesThatDoNotApplyToShape() {
        assertThrows(IllegalArgumentException.class,
                () -> StateSchema.withValue(stair(), "open", "true"));
    }
}
