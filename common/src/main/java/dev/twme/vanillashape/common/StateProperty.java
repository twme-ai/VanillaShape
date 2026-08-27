package dev.twme.vanillashape.common;

import java.util.List;
import java.util.Objects;

/** A debug-stick-editable property and its ordered set of values. */
public record StateProperty(String name, List<String> values) {
    public StateProperty {
        Objects.requireNonNull(name, "name");
        values = List.copyOf(values);
        if (values.isEmpty()) throw new IllegalArgumentException("A state property needs values");
    }
}
