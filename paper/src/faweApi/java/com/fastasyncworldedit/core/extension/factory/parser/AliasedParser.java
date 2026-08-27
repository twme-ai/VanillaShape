package com.fastasyncworldedit.core.extension.factory.parser;

import java.util.List;

/** Compile-only signature from FAWE's public parser API; not packaged in VanillaShape. */
public interface AliasedParser {
    List<String> getMatchedAliases();
}
