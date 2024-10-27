package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class StringUtilsTest {
    @Test void testReplaceMatches() {
        assertThat(StringUtils.replaceMatches("abcdef", Pattern.compile("b(cd)e"), mat -> "@" + mat.group(1).toUpperCase() + "@"), is("a@CD@f"));
        assertThat(StringUtils.replaceMatches("ab!def", Pattern.compile("b(cd)e"), mat -> { fail("should not match"); return ""; }), is("ab!def"));
    }
    @Test void testReplaceRegex() {
        assertThat(StringUtils.replaceRegex("abcdef", "b.d", g -> g[0].toUpperCase()), is("aBCDef"));
        assertThat(StringUtils.replaceRegex("abcdefghijklmn", "cd.+hij", g -> g[0].toUpperCase()), is("abCDEFGHIJklmn"));
        assertThat(StringUtils.replaceRegex("abcdefghijklmn", "cd(.+)hij", g -> g[1].toUpperCase()), is("abEFGklmn"));
        assertThat(StringUtils.replaceRegex("abcdef", "b(.)d", g -> g[1].toUpperCase()), is("aCef"));
    }
    @Test void testReplaceGroups() {
        assertThat(StringUtils.replaceGroups("a=1 b=2 c=3 d=4 e=5", "\\w=(.) \\w=(.)", "11", "22", "33", "44"), is("a=11 b=22 c=33 d=44 e=5"));
        assertThat(StringUtils.replaceGroups("a=1 b=2 c=3 d=4 e=5", "\\w=(.) \\w=(.)", "11", "22", null, "44"), is("a=11 b=22 c= d=44 e=5"));
        assertThat(StringUtils.replaceGroups("a=1 b=2 c=3 d=4 e=5", "\\w=(.) \\w=(.)", "11", "22"), is("a=11 b=22 c=11 d=22 e=5"));
        assertThat(StringUtils.replaceGroups("a=1 b=2 c=3 d=4 e=5", "\\w=(.) \\w=(.)", "11"), is("a=11 b=11 c=11 d=11 e=5"));
        assertThat(StringUtils.replaceGroups("a=1 b=2 c=3 d=4 e=5", "\\w=(.) \\w=(.)"), is("a= b= c= d= e=5"));
        assertThat(StringUtils.replaceGroups("a=1 b=2 c=3 d=4 e=5", "\\w=(.)", "X"), is("a=X b=X c=X d=X e=X"));
        assertThat(StringUtils.replaceGroups("a=1 b=2 c=3 d=4 e=5", "\\w=(?:.) \\w=(.)", "X"), is("a=1 b=X c=3 d=X e=5"));
        assertThat(StringUtils.replaceGroups("a=1 b=2", "\\w=(.)", n -> "1".equals(n) ? "@" : "@@"), is("a=@ b=@@"));
        assertThat(StringUtils.replaceGroups("back\\slash", Pattern.compile("(ash)"), n -> "ash\\es"), is("back\\slash\\es"));
    }
    @Test void testReplaceVariables() {
        assertThat(StringUtils.replaceVariables("a=${a} b=${b} c=${c}", Map.of("a","1","b","2")), is("a=1 b=2 c=?unknown:(c)?"));
        assertThat(StringUtils.replaceVariables("a=${a} b=${b} c=${c}", Map.of("a","1","b","2"), "?"), is("a=1 b=2 c=?"));
        assertThat(StringUtils.replaceVariables("a=${a} b=${b} c=${c}", a -> a.toUpperCase(Locale.ROOT)), is("a=A b=B c=C"));
    }
    @Test void testGetRegexMatch() {
        assertThat(StringUtils.getRegexMatch("abcdef", "ZZ"), is(Optional.empty()));
        assertThat(StringUtils.getRegexMatch("abcdef", "c.e"), is(Optional.of("cde")));
    }
    @Test void testGetRegexGroup() {
        assertThat(StringUtils.getRegexGroup("abcdef", "ab(ZZ)ef"), is(Optional.empty()));
        assertThat(StringUtils.getRegexGroup("abcdef", "ab(c.e)f"), is(Optional.of("cde")));
    }
    @Test void testGetRegexGroups() {
        assertThat(StringUtils.getRegexGroups("abcdef12345", "\\d\\d"), is(List.of("12", "34")));
        assertThat(StringUtils.getRegexGroups("abcdef12345", "b(.+)12(.+)5"), is(List.of("cdef", "34")));
    }

    @Test void testRemoveWhitespaces() {
        assertThat(StringUtils.removeWhitespaces("a b\nc\t   d"), is("abcd"));
    }

    @Test void testParseInt() {
        assertThat(StringUtils.parseInt("123"), is(Optional.of(123)));
        assertThat(StringUtils.parseInt("notAnInt"), is(Optional.empty()));
    }
    @Test void testParseLong() {
        assertThat(StringUtils.parseLong("123"), is(Optional.of(123L)));
        assertThat(StringUtils.parseLong("notAnNumber"), is(Optional.empty()));
    }
    @Test void testByteSizeToString() {
        assertThat(StringUtils.byteSizeToString(                    123), is("123 B"));
        assertThat(StringUtils.byteSizeToString(                   1024), is("1024 B"));
        assertThat(StringUtils.byteSizeToString(               4 * 1024), is("4096 B"));
        assertThat(StringUtils.byteSizeToString(            5 * 1024 -1), is("5119 B"));
        assertThat(StringUtils.byteSizeToString(               5 * 1024), is("5 KB"));
        assertThat(StringUtils.byteSizeToString(            1024 * 1024), is("1024 KB"));
        assertThat(StringUtils.byteSizeToString(        5 * 1024 * 1024), is("5 MB"));
        assertThat(StringUtils.byteSizeToString(8L * 1024 * 1024 * 1024), is("8 GB"));
    }

    @Test void testNotEmpty() {
        //noinspection ConstantConditions -- even the compiler agrees it is always false. Test it anyway.
        assertThat(StringUtils.isNotEmpty(null), is(false));
        assertThat(StringUtils.isNotEmpty(""), is(false));
        assertThat(StringUtils.isNotEmpty("a"), is(true));
    }
    @Test void testStartsWithUpperCase() {
        assertThat(StringUtils.startsWithUpperCase(""), is(false));
        assertThat(StringUtils.startsWithUpperCase("_"), is(true));
        assertThat(StringUtils.startsWithUpperCase("a"), is(false));
        assertThat(StringUtils.startsWithUpperCase("A"), is(true));
        assertThat(StringUtils.startsWithUpperCase("aBC"), is(false));
        assertThat(StringUtils.startsWithUpperCase("Abc"), is(true));
    }

    @Test void testObfuscation() {
        final String textIn = "This text should be obfuscated. abc\tdef\u0FFFend";
        final String obfuscated = StringUtils.obfuscate(textIn);
        final String textOut = StringUtils.unobfuscate(obfuscated);
        assertThat(textOut, is(textIn));
    }
}