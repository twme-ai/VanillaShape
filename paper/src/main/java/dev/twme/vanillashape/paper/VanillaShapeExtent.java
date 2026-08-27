package dev.twme.vanillashape.paper;

import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import dev.twme.vanillashape.common.SpecialBlock;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** WorldEdit extent that presents SQLite records as proxies and strips proxies before world writes. */
final class VanillaShapeExtent extends AbstractDelegateExtent {
    private final BlockService blocks;
    private final WorldEditProxyCodec codec;
    private final String world;
    private final boolean mutating;
    private final boolean authorized;
    private final Object pendingLock = new Object();
    private final Map<BlockPosKey, Pending> pending = new HashMap<>();
    private final Map<BlockPosKey, LinCompoundTag> splitProxyMarkers = new HashMap<>();

    VanillaShapeExtent(final Extent extent, final BlockService blocks,
                       final WorldEditProxyCodec codec, final String world,
                       final boolean mutating, final boolean authorized) {
        super(extent);
        this.blocks = blocks;
        this.codec = codec;
        this.world = world;
        this.mutating = mutating;
        this.authorized = authorized;
    }

    @Override public BlockState getBlock(final BlockVector3 position) {
        final SpecialBlock special = current(position);
        return special == null ? super.getBlock(position) : codec.encode(special).toImmutableState();
    }

    /** FAWE hot-path overload; harmless when running against standard WorldEdit. */
    public BlockState getBlock(final int x, final int y, final int z) {
        return getBlock(BlockVector3.at(x, y, z));
    }

    @Override public BaseBlock getFullBlock(final BlockVector3 position) {
        final SpecialBlock special = current(position);
        return special == null ? super.getFullBlock(position) : codec.encode(special);
    }

    /** FAWE hot-path overload; harmless when running against standard WorldEdit. */
    public BaseBlock getFullBlock(final int x, final int y, final int z) {
        return getFullBlock(BlockVector3.at(x, y, z));
    }

    @Override public <T extends BlockStateHolder<T>> boolean setBlock(
            final BlockVector3 position, final T requested) throws WorldEditException {
        if (!mutating) return super.setBlock(position, requested);
        BaseBlock base = requested.toBaseBlock();
        final SpecialBlock before = current(position);
        final BlockPosKey positionKey = key(position);
        synchronized (pendingLock) {
            final LinCompoundTag splitMarker = splitProxyMarkers.remove(positionKey);
            if (splitMarker != null && before != null && codec.isCarrier(base, before)) {
                base = base.toImmutableState().toBaseBlock(splitMarker);
            }
        }
        if (!authorized && (before != null || codec.isProxy(base))) {
            throw new VanillaShapeEditException(
                    "You do not have permission to edit VanillaShape blocks with WorldEdit");
        }
        final var decoded = codec.decode(base, world, position.x(), position.y(), position.z());
        if (decoded.isEmpty() && codec.isProxy(base)) {
            throw new VanillaShapeEditException("Invalid or unsupported VanillaShape proxy data");
        }
        if (decoded.isPresent()) {
            if (!codec.isCarrier(base, decoded.get())) {
                synchronized (pendingLock) {
                    splitProxyMarkers.put(positionKey, base.getNbt());
                }
            }
            final boolean backingChanged = super.setBlock(position,
                    Objects.requireNonNull(BlockTypes.AIR).getDefaultState());
            setPending(position, decoded.get());
            return backingChanged || !decoded.get().equals(before);
        }
        final boolean backingChanged = super.setBlock(position, requested);
        if (before != null) setPending(position, null);
        return backingChanged || before != null;
    }

    /** FAWE hot-path overload; harmless when running against standard WorldEdit. */
    public <T extends BlockStateHolder<T>> boolean setBlock(
            final int x, final int y, final int z, final T block) throws WorldEditException {
        return setBlock(BlockVector3.at(x, y, z), block);
    }

    /* FAWE adds bulk forwarding methods to AbstractDelegateExtent. Defining these here keeps
       those optimized calls inside this virtual-block interceptor instead of bypassing it. */
    public <T extends BlockStateHolder<T>> int setBlocks(final Region region, final T block)
            throws MaxChangedBlocksException {
        int changed = 0;
        for (final BlockVector3 position : region) if (setChecked(position, block)) changed++;
        return changed;
    }

    public int setBlocks(final Region region, final Pattern pattern) throws MaxChangedBlocksException {
        int changed = 0;
        for (final BlockVector3 position : region) {
            if (setChecked(position, pattern.applyBlock(position))) changed++;
        }
        return changed;
    }

    public <T extends BlockStateHolder<T>> int replaceBlocks(
            final Region region, final Set<BaseBlock> filter, final T replacement)
            throws MaxChangedBlocksException {
        return replaceBlocks(region, filter, (Pattern) replacement);
    }

    public int replaceBlocks(final Region region, final Set<BaseBlock> filter, final Pattern pattern)
            throws MaxChangedBlocksException {
        int changed = 0;
        for (final BlockVector3 position : region) {
            final BaseBlock current = getFullBlock(position);
            final boolean matches = filter == null ? !current.getBlockType().getMaterial().isAir()
                    : filter.stream().anyMatch(candidate -> candidate.equalsFuzzy(current));
            if (matches && setChecked(position, pattern.applyBlock(position))) changed++;
        }
        return changed;
    }

    public int replaceBlocks(final Region region, final Mask mask, final Pattern pattern)
            throws MaxChangedBlocksException {
        int changed = 0;
        for (final BlockVector3 position : region) {
            if (mask.test(position) && setChecked(position, pattern.applyBlock(position))) changed++;
        }
        return changed;
    }

    public int setBlocks(final Set<BlockVector3> positions, final Pattern pattern) {
        int changed = 0;
        for (final BlockVector3 position : positions) {
            try {
                if (setBlock(position, pattern.applyBlock(position))) changed++;
            } catch (final WorldEditException error) {
                throw new IllegalStateException("Could not apply VanillaShape FAWE batch", error);
            }
        }
        return changed;
    }

    @Override protected Operation commitBefore() {
        if (!mutating) return null;
        return new Operation() {
            private boolean completed;

            @Override public Operation resume(final RunContext run) throws WorldEditException {
                if (!completed) {
                    completed = true;
                    try {
                        final Map<BlockPosKey, Pending> snapshot;
                        synchronized (pendingLock) {
                            snapshot = new HashMap<>(pending);
                            pending.clear();
                            splitProxyMarkers.clear();
                        }
                        final Map<BlockPosKey, SpecialBlock> upserts = new HashMap<>();
                        final Set<BlockPosKey> removals = new HashSet<>();
                        snapshot.forEach((position, mutation) -> {
                            if (mutation.block() == null) removals.add(position);
                            else upserts.put(position, mutation.block());
                        });
                        blocks.applyExactBatch(world, upserts, removals);
                    } catch (final RuntimeException error) {
                        throw new VanillaShapeEditException("Could not commit VanillaShape block batch", error);
                    }
                }
                return null;
            }

            @Override public void cancel() { completed = true; }
        };
    }

    private SpecialBlock current(final BlockVector3 position) {
        final BlockPosKey key = key(position);
        synchronized (pendingLock) {
            final Pending mutation = pending.get(key);
            if (mutation != null) return mutation.block();
        }
        return blocks.get(world, position.x(), position.y(), position.z());
    }

    private void setPending(final BlockVector3 position, final SpecialBlock block) {
        synchronized (pendingLock) {
            pending.put(key(position), new Pending(block));
        }
    }

    private boolean setChecked(final BlockVector3 position, final BlockStateHolder<?> block)
            throws MaxChangedBlocksException {
        try {
            return setBlock(position, block.toBaseBlock());
        } catch (final MaxChangedBlocksException limit) {
            throw limit;
        } catch (final WorldEditException error) {
            throw new IllegalStateException("Could not apply VanillaShape WorldEdit batch", error);
        }
    }

    private static BlockPosKey key(final BlockVector3 position) {
        return new BlockPosKey(position.x(), position.y(), position.z());
    }

    private record Pending(SpecialBlock block) {}

    private static final class VanillaShapeEditException extends WorldEditException {
        VanillaShapeEditException(final String message) { super(TextComponent.of(message)); }
        VanillaShapeEditException(final String message, final Throwable cause) {
            super(TextComponent.of(message), cause);
        }
    }
}
