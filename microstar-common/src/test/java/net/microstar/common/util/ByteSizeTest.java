package net.microstar.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteSizeTest {
    private static final long MUL_KB = 1024;
    private static final long MUL_MB = 1024 * MUL_KB;
    private static final long MUL_GB = 1024 * MUL_MB;
    private static final long MUL_TB = 1024 * MUL_GB;
    private static final long MUL_PB = 1024 * MUL_TB;

    @Test void bytes() {
        assertThat(ByteSize.ofBytes(10).getBytes(), is(BigInteger.valueOf(10)));
        assertThat(ByteSize.ofBytes(10).getBytesInt(), is(10));
        assertThat(ByteSize.ofBytes(10).getBytesLong(), is(10L));
        assertThat(ByteSize.ofBytes(BigInteger.valueOf(10)).getBytesInt(), is(10));
        assertThat(ByteSize.ofBytes(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(100))).getBytesLongClipped(), is(Long.MAX_VALUE));
        assertFalse(ByteSize.ofBytes(1).isZero());
    }
    @Test void kiloBytes() {
        assertThat(ByteSize.ofKilobytes(BigInteger.valueOf(2)).getBytesLong(), is(2 * MUL_KB));
        final ByteSize kb = ByteSize.ofKilobytes(2);
        assertThat(kb.getBytes(), is(BigInteger.valueOf(2 * MUL_KB)));
        assertThat(kb.getBytesInt(), is(2 * (int)MUL_KB));
        assertThat(kb.getBytesLong(), is(2 * MUL_KB));
        assertThat(kb.getKilobytes(), is(BigInteger.valueOf(2)));
        assertThat(kb.getKilobytesInt(), is(2));
        assertThat(kb.getKilobytesLong(), is(2L));
    }
    @Test void megaBytes() {
        assertThat(ByteSize.ofMegabytes(BigInteger.valueOf(3)).getBytesLong(), is(3 * MUL_MB));
        final ByteSize mb = ByteSize.ofMegabytes(3);
        assertThat(mb.getBytes(), is(BigInteger.valueOf(3 * MUL_MB)));
        assertThat(mb.getBytesLong(), is(3 * MUL_MB));
        assertThat(mb.getBytesInt(), is(3 * (int)MUL_MB));
        assertThat(mb.getMegabytes(), is(BigInteger.valueOf(3)));
        assertThat(mb.getMegabytesInt(), is(3));
        assertThat(mb.getMegabytesLong(), is(3L));
    }
    @Test void gigaBytes() {
        assertThat(ByteSize.ofGigabytes(BigInteger.valueOf(4)).getBytesLong(), is(4 * MUL_GB));
        final ByteSize gb = ByteSize.ofGigabytes(4);
        assertThat(gb.getBytes(), is(BigInteger.valueOf(4 * MUL_GB)));
        assertThat(gb.getBytesLong(), is(4 * MUL_GB));
        assertThrows(ArithmeticException.class, gb::getBytesInt);
        assertThat(gb.getGigabytes(), is(BigInteger.valueOf(4)));
        assertThat(gb.getGigabytesInt(), is(4));
        assertThat(gb.getGigabytesLong(), is(4L));
    }
    @Test void teraBytes() {
        assertThat(ByteSize.ofTerabytes(BigInteger.valueOf(5)).getBytesLong(), is(5 * MUL_TB));
        final ByteSize tb = ByteSize.ofTerabytes(5);
        assertThat(tb.getBytes(), is(BigInteger.valueOf(5 * MUL_TB)));
        assertThat(tb.getBytesLong(), is(5 * MUL_TB));
        assertThrows(ArithmeticException.class, tb::getBytesInt);
        assertThat(tb.getTerabytes(), is(BigInteger.valueOf(5)));
        assertThat(tb.getTerabytesInt(), is(5));
        assertThat(tb.getTerabytesLong(), is(5L));
    }
    @Test void petaBytes() {
        assertThat(ByteSize.ofPetabytes(BigInteger.valueOf(6)).getBytesLong(), is(6 * MUL_PB));
        final ByteSize pb = ByteSize.ofPetabytes(6);
        assertThat(pb.getBytes(), is(BigInteger.valueOf(6 * MUL_PB)));
        assertThat(pb.getBytesLong(), is(6 * MUL_PB));
        assertThrows(ArithmeticException.class, pb::getBytesInt);
        assertThat(pb.getPetabytes(), is(BigInteger.valueOf(6)));
        assertThat(pb.getPetabytesInt(), is(6));
        assertThat(pb.getPetabytesLong(), is(6L));
    }
    @Test void exaBytes() {
        final BigInteger expectedResult = BigInteger.valueOf(7).multiply(ByteSize.BYTES_IN_EB);
        final ByteSize eb = ByteSize.ofExabytes(7);
        assertThat(ByteSize.ofExabytes(BigInteger.valueOf(7)).getBytes(), is(expectedResult));
        assertThat(eb.getBytes(), is(expectedResult));
        assertThrows(ArithmeticException.class, eb::getBytesInt);
        assertThat(eb.getExabytesLong(), is(7L));
        assertThat(eb.getExabytes(), is(BigInteger.valueOf(7)));
        assertThat(eb.getExabytesInt(), is(7));
        assertThat(eb.getExabytesLong(), is(7L));
    }
    @Test void zettaBytes() {
        final BigInteger expectedResult = BigInteger.valueOf(8).multiply(ByteSize.BYTES_IN_ZB);
        final ByteSize zb = ByteSize.ofZettabytes(8);
        assertThat(ByteSize.ofZettabytes(BigInteger.valueOf(8)).getBytes(), is(expectedResult));
        assertThat(zb.getBytes(), is(expectedResult));
        assertThrows(ArithmeticException.class, zb::getBytesInt);
        assertThrows(ArithmeticException.class, zb::getBytesLong);
        assertThat(zb.getZettabytes(), is(BigInteger.valueOf(8)));
        assertThat(zb.getZettabytesInt(), is(8));
        assertThat(zb.getZettabytesLong(), is(8L));
    }
    @Test void yottaBytes() {
        final BigInteger expectedResult = BigInteger.valueOf(9).multiply(ByteSize.BYTES_IN_YB);
        final ByteSize yb = ByteSize.ofYottabytes(9);
        assertThat(ByteSize.ofYottabytes(BigInteger.valueOf(9)).getBytes(), is(expectedResult));
        assertThat(yb.getBytes(), is(expectedResult));
        assertThrows(ArithmeticException.class, yb::getBytesInt);
        assertThrows(ArithmeticException.class, yb::getBytesLong);
        assertThat(yb.getYottabytes(), is(BigInteger.valueOf(9)));
        assertThat(yb.getYottabytesInt(), is(9));
        assertThat(yb.getYottabytesLong(), is(9L));
    }

    @Test void plus() {
        assertThat(ByteSize.ofBytes(100).plus(23), is(ByteSize.ofBytes(123)));
        assertThat(ByteSize.ofKilobytes(100).plus(23 * 1024), is(ByteSize.ofKilobytes(123)));
    }
    @Test void minus() {
        assertThat(ByteSize.ofBytes(100).minus(23), is(ByteSize.ofBytes(77)));
        assertThat(ByteSize.ofKilobytes(100).minus(23 * 1024), is(ByteSize.ofKilobytes(77)));
    }
    @Test void zeroShouldBe0() {
        assertThat(ByteSize.ZERO.getBytesInt(), is(0));
        assertTrue(ByteSize.ZERO.isZero());
    }

    @Test void testComparing() {
        final ByteSize size = ByteSize.ofBytes(10);
        assertTrue(size.isSmallerThan(ByteSize.ofBytes(11)));
        assertTrue(size.isSmallerThan(11));
        assertTrue(size.isEqualTo(ByteSize.ofBytes(10)));
        assertTrue(size.isEqualTo(10));
        assertTrue(size.isGreaterThan(ByteSize.ofBytes(1)));
        assertTrue(size.isGreaterThan(1));
    }

    @Test void jacksonSerializationShouldNotUseGetters() throws JsonProcessingException {
        final ByteSize byteSize = ByteSize.ofKilobytes(123);
        final String json = new ObjectMapper().writeValueAsString(byteSize);
        assertThat(json, not(containsString("megabytesInt")));

        final ByteSize bs = new ObjectMapper().readValue(json, ByteSize.class);
        assertThat(bs, equalTo(byteSize));
    }
}