package net.microstar.common.conversions;

import net.microstar.common.util.ByteSize;
import net.microstar.common.util.CollectionUtils;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.StringUtils;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.is;

/** Conversion between ByteSize and String */
@SuppressWarnings("squid:S3973"/*conditions indents*/)
public final class ByteSizeString {
    private ByteSizeString() {}
    private static final Pattern TUPLE_SPLIT_PATTERN = Pattern.compile("(?<=\\D)(?=\\d)");
    private static final Pattern TUPLE_PARTS_SPLIT_PATTERN = Pattern.compile("(?=\\D)");
    private static final BigInteger MAG = BigInteger.valueOf(1024);
    private static final Map<String,BigInteger> suffixMagnitudes = ImmutableUtil.mapOf(
        "", BigInteger.ONE,
        "K", ByteSize.BYTES_IN_KB,
        "M", ByteSize.BYTES_IN_MB,
        "G", ByteSize.BYTES_IN_GB,
        "T", ByteSize.BYTES_IN_TB,
        "P", ByteSize.BYTES_IN_PB,
        "E", ByteSize.BYTES_IN_EB,
        "Z", ByteSize.BYTES_IN_ZB,
        "Y", ByteSize.BYTES_IN_YB
    );

    public static String toString(ByteSize size) {
        final String initialSuffix = suffixMagnitudes.entrySet().stream()
            .filter(entry -> is(size.getBytes()).smallerThan(entry.getValue().multiply(MAG)))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow();
        final String initial = size.getBytes().divide(suffixMagnitudes.get(initialSuffix)) + initialSuffix;
        final String rest = CollectionUtils.reverse(suffixMagnitudes.keySet().stream())
            .reduce("", (all, suffix) -> all + modulo(size.getBytes().divide(suffixMagnitudes.get(suffix)), suffix));
        return initial + rest + "B";
    }
    public static ByteSize fromString(String s) {
        // Do a toUpperCase to fix often made mistakes like the 'B' vs 'b'.
        // Where 'B' means 'bytes' and 'b' means 'bits', almost always 'bytes'
        // is meant in both cases. Also, while 'p' means 'pico' the 'Peta' will
        // be the idea, like 'm' (milli) vs 'M' (mega).
        final String sNormalized = StringUtils.removeWhitespaces(s.toUpperCase(Locale.ROOT));
        final BigInteger bytes = Stream.of(
            TUPLE_SPLIT_PATTERN.split(sNormalized))
            .map(TUPLE_PARTS_SPLIT_PATTERN::split)
            .map(parts -> {
                final long number = noThrow(() -> Long.parseLong(parts[0]))
                    .orElseThrow(() -> new IllegalArgumentException("Unexpected number in " + List.of(parts)));

                final BigInteger magnitude = parts.length == 1 || "B".equals(parts[1])
                    ? BigInteger.ONE
                    : Optional.ofNullable(suffixMagnitudes.get(parts[1]))
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported magnitude in " + List.of(parts)));

                return magnitude.multiply(BigInteger.valueOf(number));
            })
            .reduce(BigInteger.ZERO, BigInteger::add);

        return ByteSize.ofBytes(bytes);
    }

    private static String modulo(BigInteger size, String suffix) {
        //            size > modValue       && size % modValue != 0
        return size.compareTo(MAG) > 0 && size.mod(MAG).intValue() != 0
            ? size.mod(MAG) + suffix
            : "";
    }
}
