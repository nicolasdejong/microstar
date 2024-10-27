package net.microstar.common.util;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Pattern;

/** <pre>
 * Comparator for strings that have numbers in them and where lower numbers come
 * before higher numbers (so name-2 is before name-10 and 1.2.3 is before 1.11.3).
 *
 * From <a href="https://blog.jooq.org/how-to-order-file-names-semantically-in-java/">a blog on Jooq.org</a>
 * by Lukas Eder
 *
 * There are faster versions around, but nothing as simple and succinct as this one.
 */
public final class SemanticStringComparator implements Comparator<String> {
    public static final SemanticStringComparator DEFAULT = new SemanticStringComparator(false);
    public static final SemanticStringComparator IGNORING_CASE = new SemanticStringComparator(true);
    private static final Pattern NUMBERS = Pattern.compile("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
    private final boolean ignoreCase;

    private SemanticStringComparator(boolean ignoreCase) { this.ignoreCase = ignoreCase; }

    @Override
    public int compare(@Nullable String o1, @Nullable String o2) { // NOSONAR -- slightly too complex but quite readable (and 3rd party code)
        // Optional "NULLS LAST" semantics:
        if (o1 == null || o2 == null)
            return o1 == null ? o2 == null ? 0 : -1 : 1;
        // Splitting both input strings by the above patterns
        final String[] split1 = NUMBERS.split(o1);
        final String[] split2 = NUMBERS.split(o2);
        for (int i = 0; i < Math.min(split1.length, split2.length); i++) {
            final char c1 = split1[i].length() > 0 ? split1[i].charAt(0) : 0;
            final char c2 = split2[i].length() > 0 ? split2[i].charAt(0) : 0;
            int cmp = 0;
            // If both segments start with a digit, sort them numerically using
            // BigInteger to stay safe
            if(Character.isDigit(c1) && Character.isDigit(c2))
                //noinspection ObjectInstantiationInEqualsHashCode -- can't be helped, we need BigInteger to compare
                cmp = new BigInteger(split1[i]).compareTo(new BigInteger(split2[i]));
            // If we haven't sorted numerically before, or if numeric sorting yielded
            // equality (e.g 007 and 7) then sort lexicographically
            if (cmp == 0)
                cmp = ignoreCase ? split1[i].compareToIgnoreCase(split2[i]) : split1[i].compareTo(split2[i]);
            // Abort once some prefix has unequal ordering
            if (cmp != 0)
                return cmp;
        }
        // If we reach this, then both strings have equally ordered prefixes, but
        // maybe one string is longer than the other (i.e. has more segments)
        return split1.length - split2.length;
    }
}