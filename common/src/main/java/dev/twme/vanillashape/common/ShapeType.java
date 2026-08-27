package dev.twme.vanillashape.common;

import java.util.Locale;

/** Shapes understood by both the Paper authority and Fabric renderer. */
public enum ShapeType {
    WALL,
    FENCE,
    FENCE_GATE,
    SLAB,
    STAIRS,
    DOOR,
    TRAPDOOR,
    VERTICAL_SLAB,
    /** Uses an arbitrary vanilla BlockData baked model as geometry. */
    MODEL;

    public static ShapeType parse(final String input) {
        return valueOf(input.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
