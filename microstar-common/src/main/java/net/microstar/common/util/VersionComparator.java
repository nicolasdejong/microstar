package net.microstar.common.util;

import java.util.Comparator;
import java.util.Locale;

/** Version comparator is equal to SemanticStringComparator except SNAPSHOT versions are older */
public class VersionComparator implements Comparator<String> {
    public static final VersionComparator OLDEST_TO_NEWEST = new VersionComparator(false);
    public static final VersionComparator NEWEST_TO_OLDEST = new VersionComparator(true);
    private static final SemanticStringComparator semanticStringComparator = SemanticStringComparator.IGNORING_CASE;
    private final boolean reversed;
    private VersionComparator(boolean reversed) { this.reversed = reversed; }

    @Override
    public int compare(String a, String b) {
        final boolean aIsSnapshot = isSnapshot(a);
        final boolean bIsSnapshot = isSnapshot(b);
        final String a0 = aIsSnapshot ? a.replaceFirst("(?i)-?SNAPSHOT", "") : a;
        final String b0 = bIsSnapshot ? b.replaceFirst("(?i)-?SNAPSHOT", "") : b;
        int cmp = semanticStringComparator.compare(a0, b0);
        if(cmp == 0 && aIsSnapshot != bIsSnapshot) cmp = aIsSnapshot ? -1 : 1;
        return reversed ? -cmp : cmp;
    }

    private static boolean isSnapshot(String a) {
        return a.toLowerCase(Locale.ROOT).contains("snapshot");
    }
}
