package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import org.junit.jupiter.api.Test;

import java.io.Flushable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaShapeClipboardLifecycleTest {
    @Test void replacementHolderRetainsDelegateUntilWrapperIsClosed() {
        final BlockVector3 position = BlockVector3.at(4, 80, 7);
        final Clipboard raw = new BlockArrayClipboard(new CuboidRegion(position, position));
        final TrackingClipboard retained = new TrackingClipboard(raw);
        final Clipboard wrapped = new VanillaShapeClipboard(
                retained, new WorldEditProxyCodec(), java.util.Map.of());
        final VanillaShapeClipboardHolder holder = new VanillaShapeClipboardHolder(wrapped, retained);

        // This is the virtual call FAWE LocalSession#setClipboard uses before closing
        // the old holder. It must claim the raw clipboard now owned by the wrapper.
        assertTrue(holder.contains(retained));
        assertTrue(holder.contains(wrapped));
        assertFalse(retained.closed);

        ((VanillaShapeClipboard) wrapped).flush();
        assertTrue(retained.flushed);
        assertFalse(retained.closed);

        ((VanillaShapeClipboard) wrapped).close();
        assertTrue(retained.closed);
    }

    private static final class TrackingClipboard extends AbstractDelegateExtent
            implements Clipboard, AutoCloseable, Flushable {
        private final Clipboard delegate;
        private boolean closed;
        private boolean flushed;

        private TrackingClipboard(final Clipboard delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        @Override public Region getRegion() { return delegate.getRegion(); }
        @Override public BlockVector3 getDimensions() { return delegate.getDimensions(); }
        @Override public BlockVector3 getOrigin() { return delegate.getOrigin(); }
        @Override public void setOrigin(final BlockVector3 origin) { delegate.setOrigin(origin); }
        @Override public boolean hasBiomes() { return delegate.hasBiomes(); }
        @Override public void flush() { flushed = true; }
        @Override public void close() { closed = true; }
    }
}
