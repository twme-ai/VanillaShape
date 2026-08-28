package dev.twme.vanillashape.paper;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import dev.twme.vanillashape.common.SpecialBlock;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.io.Flushable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Clipboard view that retains proxy NBT even when FAWE hides NBT on non-container carriers. */
final class VanillaShapeClipboard extends AbstractDelegateExtent implements Clipboard {
    private final Clipboard delegate;
    private final WorldEditProxyCodec codec;
    private final Map<BlockVector3, BaseBlock> proxies;
    private boolean closed;

    VanillaShapeClipboard(final Clipboard delegate, final WorldEditProxyCodec codec,
                          final Map<BlockVector3, BaseBlock> proxies) {
        super(delegate);
        this.delegate = delegate;
        this.codec = codec;
        this.proxies = new HashMap<>(proxies);
    }

    static Clipboard fromWorldSnapshot(final Clipboard delegate, final WorldEditProxyCodec codec,
                                       final List<SpecialBlock> blocks) {
        final Map<BlockVector3, BaseBlock> proxies = new HashMap<>();
        for (final SpecialBlock block : blocks) {
            final BlockVector3 position = BlockVector3.at(block.x(), block.y(), block.z());
            if (delegate.getRegion().contains(position)) proxies.put(position, codec.encode(block));
        }
        return proxies.isEmpty() ? delegate : new VanillaShapeClipboard(delegate, codec, proxies);
    }

    static Clipboard recoverFaweTiles(final Clipboard delegate, final WorldEditProxyCodec codec) {
        final Map<BlockVector3, BaseBlock> proxies = new HashMap<>();
        try {
            final Method getParent = delegate.getClass().getMethod("getParent");
            final Object parent = getParent.invoke(delegate);
            final Method getTiles = parent.getClass().getMethod("getTileEntities");
            final Object result = getTiles.invoke(parent);
            if (result instanceof Collection<?> tiles) {
                final BlockVector3 minimum = delegate.getRegion().getMinimumPoint();
                for (final Object value : tiles) {
                    if (!(value instanceof CompoundTag tile)) continue;
                    final LinCompoundTag marker = tile.toLinTag();
                    BlockVector3 position = BlockVector3.at(
                            tile.getInt("x"), tile.getInt("y"), tile.getInt("z"));
                    if (!delegate.getRegion().contains(position)) position = minimum.add(position);
                    if (!delegate.getRegion().contains(position)) continue;
                    final BaseBlock proxy = delegate.getBlock(position).toBaseBlock(marker);
                    if (codec.isProxy(proxy)) proxies.put(position, proxy);
                }
            }
        } catch (final ReflectiveOperationException ignored) {
            return delegate;
        }
        return proxies.isEmpty() ? delegate : new VanillaShapeClipboard(delegate, codec, proxies);
    }

    @Override public Region getRegion() { return delegate.getRegion(); }
    @Override public BlockVector3 getDimensions() { return delegate.getDimensions(); }
    @Override public BlockVector3 getOrigin() { return delegate.getOrigin(); }
    @Override public void setOrigin(final BlockVector3 origin) { delegate.setOrigin(origin); }
    @Override public boolean hasBiomes() { return delegate.hasBiomes(); }

    @Override public BlockState getBlock(final BlockVector3 position) {
        final BaseBlock proxy = proxies.get(position);
        return proxy == null ? super.getBlock(position) : proxy.toImmutableState();
    }

    @Override public BaseBlock getFullBlock(final BlockVector3 position) {
        final BaseBlock proxy = proxies.get(position);
        return proxy == null ? super.getFullBlock(position) : proxy;
    }

    @Override public <T extends BlockStateHolder<T>> boolean setBlock(
        final BlockVector3 position, final T requested) throws WorldEditException {
        final BaseBlock block = requested.toBaseBlock();
        if (codec.isProxy(block)) {
            proxies.put(position, block);
            return super.setBlock(position, block.toImmutableState());
        }
        proxies.remove(position);
        return super.setBlock(position, requested);
    }

    /**
     * FAWE clipboards own mapped files. The wrapper takes over that ownership and
     * releases it only when its replacement holder is eventually closed.
     */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (delegate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (final IOException error) {
                throw new UncheckedIOException("Could not close the wrapped WorldEdit clipboard", error);
            } catch (final Exception error) {
                throw new IllegalStateException("Could not close the wrapped WorldEdit clipboard", error);
            }
        }
    }

    /** FAWE persists mapped clipboard metadata during flush. */
    public void flush() {
        if (delegate instanceof Flushable flushable) {
            try {
                flushable.flush();
            } catch (final IOException error) {
                throw new UncheckedIOException("Could not flush the wrapped WorldEdit clipboard", error);
            }
        }
    }

    /** Runtime FAWE Clipboard compatibility; these are default/absent in standard WorldEdit. */
    public Iterator<BlockVector3> iterator() { return getRegion().iterator(); }
    public void removeEntity(final Entity entity) { entity.remove(); }
}
