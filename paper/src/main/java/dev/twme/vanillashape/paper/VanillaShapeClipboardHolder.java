package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.session.ClipboardHolder;

/**
 * Transfers ownership of an existing FAWE clipboard to a VanillaShape wrapper.
 *
 * <p>WorldEdit 7.4.5 does not expose {@code ClipboardHolder.contains}, while FAWE
 * adds it and calls it from {@code LocalSession.setClipboard} before closing the
 * old holder. Keeping this method free of {@code @Override} lets the plugin
 * compile against WorldEdit and dynamically override the FAWE method at runtime.</p>
 */
final class VanillaShapeClipboardHolder extends ClipboardHolder {
    private final Clipboard retained;

    VanillaShapeClipboardHolder(final Clipboard wrapped, final Clipboard retained) {
        super(wrapped);
        this.retained = retained;
    }

    /** Called virtually by FAWE 2.15.x when deciding whether it may close the old holder. */
    public boolean contains(final Clipboard candidate) {
        return candidate == getClipboard() || candidate == retained;
    }
}
