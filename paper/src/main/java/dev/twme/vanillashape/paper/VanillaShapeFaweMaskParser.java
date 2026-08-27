package dev.twme.vanillashape.paper;

import com.fastasyncworldedit.core.extension.factory.parser.AliasedParser;
import com.sk89q.worldedit.WorldEdit;

import java.util.AbstractList;
import java.util.List;
import java.util.function.UnaryOperator;

/** Makes FAWE's rich-mask front end route the whole VanillaShape namespace to our parser. */
final class VanillaShapeFaweMaskParser extends VanillaShapeMaskParser implements AliasedParser {
    private static final List<String> NAMESPACE_ALIAS = new AbstractList<>() {
        @Override public String get(final int index) {
            if (index != 0) throw new IndexOutOfBoundsException(index);
            return WorldEditBlockSpec.PREFIX;
        }

        @Override public int size() { return 1; }

        @Override public boolean contains(final Object value) {
            return value instanceof String text
                    && text.regionMatches(true, 0, WorldEditBlockSpec.PREFIX,
                    0, WorldEditBlockSpec.PREFIX.length());
        }
    };

    VanillaShapeFaweMaskParser(final WorldEdit worldEdit, final BlockService blocks,
                               final WorldEditProxyCodec codec,
                               final UnaryOperator<String> materialNormalizer) {
        super(worldEdit, blocks, codec, materialNormalizer);
    }

    @Override public List<String> getMatchedAliases() { return NAMESPACE_ALIAS; }
}
