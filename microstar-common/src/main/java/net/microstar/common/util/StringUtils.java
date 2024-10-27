package net.microstar.common.util;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Character.MAX_RADIX;

public final class StringUtils {
    private StringUtils() {}
    private static final Pattern WHITESPACES_PATTERN = Pattern.compile("\\s+");

    public static Optional<String> getRegexGroup(String input, String regex) {
        return getRegexGroup(input, Pattern.compile(regex));
    }
    public static Optional<String> getRegexGroup(String input, Pattern regex) {
        final Matcher matcher = regex.matcher(input);
        if(!matcher.find()) return Optional.empty();
        if(matcher.groupCount() < 1) return Optional.ofNullable(matcher.group());
        return Optional.of(matcher.group(1));
    }
    public static Optional<String> getRegexMatch(String input, String regex) {
        return getRegexGroup(input, "(" + Utils.firstNotNull(regex,"") + ")");
    }
    public static List<String> getRegexGroups(String input, String regex) {
        return getRegexGroups(input, Pattern.compile(regex));
    }
    @SuppressWarnings("MethodWithMultipleLoops") // current code is easier to read than to move the while body to a separate method
    public static List<String> getRegexGroups(String input, Pattern regex) {
        final List<String> results = new ArrayList<>();
        final Matcher matcher = regex.matcher(input);
        while(matcher.find()) {
            if(matcher.groupCount() == 0) results.add(matcher.group());
            for (int i = 0; i < matcher.groupCount(); i++) {
                results.add(matcher.group(i + 1));
            }
        }
        return results;
    }

    public static String replaceMatches(String input, String regex, Function<Matcher, String> replacer) { return replaceMatches(input, Pattern.compile(regex), replacer); }
    public static String replaceMatches(String input, Pattern pattern, Function<Matcher, String> replacer) {
        final Matcher matcher = pattern.matcher(Utils.firstNotNull(input,""));
        final StringBuilder replaced = new StringBuilder();

        while (matcher.find()) {
            matcher.appendReplacement(replaced, Utils.firstNotNull(replacer.apply(matcher), ""));
        }
        matcher.appendTail(replaced);
        return replaced.toString();
    }
    public static String replacePattern(String input, Pattern pattern, UnaryOperator<String> replacer) {
        return replaceMatches(input, pattern, mat -> replacer.apply(mat.groupCount() > 0 ? mat.group(1) : mat.group()));
    }
    public static String replaceRegex(String input, String regex, Function<String[],String> replacer) {
        return replaceMatches(input, Pattern.compile(regex), mat -> {
            final String[] groups = new String[mat.groupCount() + 1];
            groups[0] = mat.group();
            for(int i=1; i<=mat.groupCount(); i++) groups[i] = mat.group(i);
            return replacer.apply(groups);
        });
    }
    public static String replaceGroups(String input, Pattern regex, String... replacements) {
        final AtomicInteger index = new AtomicInteger(0);
        return replaceGroups(input, regex, toReplace -> replacements.length == 0 ? "" : replacements[index.getAndIncrement() % replacements.length]);
    }
    public static String replaceGroups(String input, Pattern regex, UnaryOperator<String> replacer) {
        return replaceMatches(input, regex, mat -> {
            final int offset = mat.start();
            if(mat.groupCount() == 0) {
                final String replacement = Utils.firstNotNull(replacer.apply(input), "");
                return new StringBuilder(mat.group()).replace(0, mat.end() - offset, replacement).toString();
            }
            final StringBuilder sb = new StringBuilder(mat.group());
            final String[] replacements = new String[mat.groupCount()];
            for (int i = 0; i < replacements.length; i++) replacements[i] = replacer.apply(mat.group(i+1));
            for (int i = mat.groupCount() - 1; i >=0; i--) {
                sb.replace(mat.start(i + 1) - offset, mat.end(i + 1) - offset, Utils.firstNotNull(replacements[i], ""));
            }
            return sb.toString().replace("\\", "\\\\"); // escape the regex escape character
        });
    }
    public static String replaceGroups(String input, String regex, String... replacements) {
        return replaceGroups(input, Pattern.compile(Utils.firstNotNull(regex,"")), replacements);
    }
    public static String replaceGroups(String input, String regex, UnaryOperator<String> replacer) {
        return replaceGroups(input, Pattern.compile(Utils.firstNotNull(regex,"")), replacer);
    }

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("(?<!\\\\)\\$\\{([^}]+)}");
    public static String replaceVariables(String textWithVars, Map<String,String> variableMap) {
        return replaceVariables(textWithVars, variableMap, "?unknown:(${var})?");
    }
    public static String replaceVariables(String textWithVars, Map<String,String> variableMap, String notFoundText) {
        return replaceVariables(textWithVars, name ->
            Optional.of(variableMap).map(map -> map.get(name))
                .orElseGet(() -> replaceVariables(notFoundText, Map.of("var",name)))
        );
    }
    public static String replaceVariables(String textWithVars, UnaryOperator<String> varMapper) {
        return StringUtils.replacePattern(textWithVars, VARIABLE_PATTERN, name ->
            Matcher.quoteReplacement(varMapper.apply(name))
        ).replace("\\${", "${");
    }

    public static String removeWhitespaces(String text) {
        return WHITESPACES_PATTERN.matcher(text).replaceAll("");
    }

    public static Optional<Integer> parseInt(String numberText) {
        try {
            return Optional.of(Integer.parseInt(numberText));
        } catch(final NumberFormatException notAnInt) {
            return Optional.empty();
        }
    }
    public static Optional<Long> parseLong(String numberText) {
        try {
            return Optional.of(Long.parseLong(numberText));
        } catch(final NumberFormatException notALong) {
            return Optional.empty();
        }
    }

    private static final int ONE_K = 1024;
    public static String byteSizeToString(long sizeIn) {
        final String[] suffixes = { "", "K", "M", "G", "T", "P" }; // higher won't fit in long
        int exp = Math.min(suffixes.length-1, (int)(Math.log(sizeIn) / Math.log(ONE_K)));
        long size = sizeIn / (long)Math.pow(ONE_K, exp);
        if(size <= 4 && exp > 0) { exp--; size = sizeIn / (long)Math.pow(ONE_K, exp); }
        return size + " " + suffixes[exp] + "B";
    }

    public static boolean isNotEmpty(@Nullable String s) {
        return s != null && !s.isEmpty();
    }

    public static boolean startsWithUpperCase(String s) {
        return s.length() > 0
            && (
                   s.startsWith("_")
                || Character.isUpperCase(s.substring(0, 1).charAt(0))
               );
    }

    // Data needed for de/obfuscation
    private static final String OBF_PREFIX = "!@O"; // This prefix denotes that a string is obfuscated
    private static final String CLI_DANGEROUS_CHARS = "[]{}\"\\$&%";
    private static final String FS_DANGEROUS_CHARS = ":\\/<>|?*";
    private static final String OBF_CHARS_ORDERED = IntStream.range(33,126) // any other characters will be encoded base(max_radix)
        .mapToObj(n -> (char)n)
        .filter(c -> CLI_DANGEROUS_CHARS.indexOf(c) < 0)
        .filter(c ->  FS_DANGEROUS_CHARS.indexOf(c) < 0)
        .map(String::valueOf)
        .collect(Collectors.joining());
    private static final String OBF_CHARS_SHUFFLED = String.join("",
        CollectionUtils.shuffledCopy(
            new ArrayList<>(Arrays.asList(OBF_CHARS_ORDERED.split(""))),
            new Random(0xC0DEFABEL) // always use the same seed so the order is predictable
        ));

    /** Simple plain-text string obfuscation that provides no security but prevents easy reading. */
    public static String obfuscate(String s) {
        final StringBuilder out = new StringBuilder();
        for(final char c : s.toCharArray()) {
            final int index = OBF_CHARS_ORDERED.indexOf(c);

            if(index < 0) {
                out.append("~");
                out.append(Integer.toString(c, MAX_RADIX));
                out.append(",");
            } else {
                out.append(OBF_CHARS_SHUFFLED.charAt(index));
            }
        }
        return OBF_PREFIX + out;
    }

    /** Reverts the obfuscation by {@link #obfuscate(String)} -- returns as-is when not obfuscated */
    public static String unobfuscate(String s) {
        if(!s.startsWith(OBF_PREFIX)) return s;
        final StringBuilder out = new StringBuilder();
        final char[] chars = s.substring(OBF_PREFIX.length()).toCharArray();
        for(int i=0; i<chars.length; i++) {
            final char c = chars[i];
            if(c == '~') {
                i++; // NOSONAR -- i touched
                final StringBuilder code = new StringBuilder();
                while(chars[i] != ',') code.append(chars[i++]);  // NOSONAR -- i touched
                out.append((char)Integer.parseInt(code.toString(), MAX_RADIX));
            } else {
                final int index = OBF_CHARS_SHUFFLED.indexOf(c);
                if(index < 0) throw new IllegalArgumentException("Obfuscated text contains illegal character: " + c);
                out.append(OBF_CHARS_ORDERED.charAt(index));
            }
        }
        return out.toString();
    }

    /** Gets and unobfuscates a system property (as given in command line with the -D option) -- returns value as-is when not obfuscated (so then behaves like System.getProperty())
      * See: {@link #unobfuscate(String)}
      */
    public static Optional<String> getObfuscatedSystemProperty(String key) {
        return Optional.ofNullable(System.getProperty(key))
            .map(StringUtils::unobfuscate);
    }
}
