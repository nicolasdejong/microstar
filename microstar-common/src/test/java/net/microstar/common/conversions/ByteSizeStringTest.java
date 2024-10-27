package net.microstar.common.conversions;

import net.microstar.common.util.ByteSize;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class ByteSizeStringTest {

    @Test void testToString() {
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(1024)), is("1KB"));
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(1024 + 1)), is("1K1B"));
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(3 * 1024)), is("3KB"));
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(3 * 1024 + 1)), is("3K1B"));
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(3 * 1024 * 1024)), is("3MB"));
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(3 * 1024 * 1024 + 5)), is("3M5B"));
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(3 * 1024 * 1024 + (2 * 1024) + 5)), is("3M2K5B"));
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(4L * 1024 * 1024 * 1024 + (3 * 1024 * 1024) + (2 * 1024) + 1)), is("4G3M2K1B"));
        assertThat(ByteSizeString.toString(ByteSize.ofBytes(ByteSize.BYTES_IN_ZB.multiply(BigInteger.valueOf(5)).add(BigInteger.valueOf(2*1024+1)))), is("5Z2K1B"));
    }
    @Test void testFromString() {
        assertThat(ByteSizeString.fromString("1KB"), is(ByteSize.ofBytes(1024)));
        assertThat(ByteSizeString.fromString("1KB1B"), is(ByteSize.ofBytes(1025)));
        assertThat(ByteSizeString.fromString("3KB"), is(ByteSize.ofBytes(3 * 1024)));
        assertThat(ByteSizeString.fromString("3K1B"), is(ByteSize.ofBytes(3 * 1024 + 1)));
        assertThat(ByteSizeString.fromString("3M5B"), is(ByteSize.ofBytes(3 * 1024 * 1024 + 5)));
        assertThat(ByteSizeString.fromString("3M2K5B"), is(ByteSize.ofBytes(3 * 1024 * 1024 + (2 * 1024) + 5)));
        assertThat(ByteSizeString.fromString("4G3M2K1B"), is(ByteSize.ofBytes(4L * 1024 * 1024 * 1024 + (3 * 1024 * 1024) + (2 * 1024) + 1)));
        assertThat(ByteSizeString.fromString("5Z2K1B"), is(ByteSize.ofBytes(ByteSize.BYTES_IN_ZB.multiply(BigInteger.valueOf(5)).add(BigInteger.valueOf(2*1024+1)))));
    }
    @Test void testFromLessFormalString() {
        assertThat(ByteSizeString.fromString("123"), is(ByteSize.ofBytes(123)));
        assertThat(ByteSizeString.fromString("123B"), is(ByteSize.ofBytes(123)));
        assertThat(ByteSizeString.fromString("3K"), is(ByteSize.ofBytes(3*1024)));
        assertThat(ByteSizeString.fromString("3k"), is(ByteSize.ofBytes(3*1024)));
        assertThat(ByteSizeString.fromString("3 k"), is(ByteSize.ofBytes(3*1024)));
        assertThat(ByteSizeString.fromString("3 kb"), is(ByteSize.ofBytes(3*1024)));
        assertThat(ByteSizeString.fromString("3 kilobytes"), is(ByteSize.ofBytes(3*1024)));
        assertThat(ByteSizeString.fromString("3 kilobytes and 5 bytes"), is(ByteSize.ofBytes(3*1024 + 5)));
        assertThat(ByteSizeString.fromString("3 kb + 5MB"), is(ByteSize.ofBytes((5*1024*1024) + (3*1024))));
        assertThat(ByteSizeString.fromString("1025 kb + 5MB"), is(ByteSize.ofBytes((6*1024*1024) + 1024)));
        assertThat(ByteSizeString.fromString("5000MB300KB"), is(ByteSize.ofBytes((5000L*1024*1024) + (300*1024))));
        assertThat(ByteSizeString.fromString("2B 1KB 2KB 3B"), is(ByteSize.ofBytes((3*1024) + 5)));
    }
}