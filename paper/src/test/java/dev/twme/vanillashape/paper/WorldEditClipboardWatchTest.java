package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldEditClipboardWatchTest {
    @Test void waitsForClipboardCreatedByCurrentAsyncCommand() {
        final Clipboard oldClipboard = clipboard(0);
        final ClipboardHolder oldHolder = new ClipboardHolder(oldClipboard);

        assertFalse(WorldEditIntegration.newClipboard(oldHolder, null));
        assertFalse(WorldEditIntegration.newClipboard(oldHolder, oldHolder));
        // A holder replacement alone is not sufficient: FAWE may transfer the same clipboard
        // while a command is still running.
        assertFalse(WorldEditIntegration.newClipboard(oldHolder, new ClipboardHolder(oldClipboard)));
        assertTrue(WorldEditIntegration.newClipboard(oldHolder, new ClipboardHolder(clipboard(1))));
    }

    @Test void acceptsFirstClipboardWhenSessionWasInitiallyEmpty() {
        assertFalse(WorldEditIntegration.newClipboard(null, null));
        assertTrue(WorldEditIntegration.newClipboard(null, new ClipboardHolder(clipboard(2))));
    }

    private static Clipboard clipboard(final int x) {
        final BlockVector3 point = BlockVector3.at(x, 64, 0);
        return new BlockArrayClipboard(new CuboidRegion(point, point));
    }
}
