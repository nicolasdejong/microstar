package net.microstar.common.util;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.util.Comparator;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static net.microstar.common.util.Utils.is;

/** This class represents a number of bytes.
  * See {@link net.microstar.common.conversions.ByteSizeString} for String representation.
  */
@EqualsAndHashCode
@JsonAutoDetect(fieldVisibility = ANY, getterVisibility = NONE, isGetterVisibility = NONE)
public class ByteSize implements Comparable<ByteSize>, Comparator<ByteSize> {
    private static final BigInteger MAGNITUDE = BigInteger.valueOf(1024);
    public static final ByteSize ZERO = ByteSize.ofBytes(0);
    public static final ByteSize MAX_LONG_SIZE = ByteSize.ofBytes(Long.MAX_VALUE);
    public static final BigInteger BYTES_IN_KB = MAGNITUDE; // x 1
    public static final BigInteger BYTES_IN_MB = BYTES_IN_KB.multiply(MAGNITUDE);
    public static final BigInteger BYTES_IN_GB = BYTES_IN_MB.multiply(MAGNITUDE);
    public static final BigInteger BYTES_IN_TB = BYTES_IN_GB.multiply(MAGNITUDE);
    public static final BigInteger BYTES_IN_PB = BYTES_IN_TB.multiply(MAGNITUDE);
    public static final BigInteger BYTES_IN_EB = BYTES_IN_PB.multiply(MAGNITUDE);
    public static final BigInteger BYTES_IN_ZB = BYTES_IN_EB.multiply(MAGNITUDE);
    public static final BigInteger BYTES_IN_YB = BYTES_IN_ZB.multiply(MAGNITUDE);
    private final BigInteger byteCount;

    public ByteSize(long size)            { this(BigInteger.valueOf(size)); }
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public ByteSize(@JsonProperty("byteCount") BigInteger byteCount) { this.byteCount = byteCount; }

    public static ByteSize ofBytes(long bytes)         { return new ByteSize(bytes); }
    public static ByteSize ofBytes(BigInteger bytes)   { return new ByteSize(bytes); }

    public static ByteSize ofKilobytes(long kb)        { return ofKilobytes(BigInteger.valueOf(kb)); }
    public static ByteSize ofKilobytes(BigInteger kb)  { return ofBytes(kb.multiply(BYTES_IN_KB)); }

    public static ByteSize ofMegabytes(long mb)        { return ofMegabytes(BigInteger.valueOf(mb)); }
    public static ByteSize ofMegabytes(BigInteger mb)  { return ofBytes(mb.multiply(BYTES_IN_MB)); }

    public static ByteSize ofGigabytes(long gb)        { return ofGigabytes(BigInteger.valueOf(gb)); }
    public static ByteSize ofGigabytes(BigInteger gb)  { return ofBytes(gb.multiply(BYTES_IN_GB)); }

    public static ByteSize ofTerabytes(long tb)        { return ofTerabytes(BigInteger.valueOf(tb)); }
    public static ByteSize ofTerabytes(BigInteger tb)  { return ofBytes(tb.multiply(BYTES_IN_TB)); }

    public static ByteSize ofPetabytes(long pb)        { return ofPetabytes(BigInteger.valueOf(pb)); }
    public static ByteSize ofPetabytes(BigInteger pb)  { return ofBytes(pb.multiply(BYTES_IN_PB)); }

    public static ByteSize ofExabytes(long eb)         { return ofExabytes(BigInteger.valueOf(eb)); }
    public static ByteSize ofExabytes(BigInteger eb)   { return ofBytes(eb.multiply(BYTES_IN_EB)); }

    public static ByteSize ofZettabytes(long zb)       { return ofZettabytes(BigInteger.valueOf(zb)); }
    public static ByteSize ofZettabytes(BigInteger zb) { return ofBytes(zb.multiply(BYTES_IN_ZB)); }

    public static ByteSize ofYottabytes(long yb)       { return ofYottabytes(BigInteger.valueOf(yb)); }
    public static ByteSize ofYottabytes(BigInteger yb) { return ofBytes(yb.multiply(BYTES_IN_YB)); }

    public BigInteger getBytes()         { return byteCount; }
    public long       getBytesLong()     { return byteCount.longValueExact(); } // this will throw if byteCount > max long
    public long       getBytesLongClipped() { return isGreaterThan(MAX_LONG_SIZE) ? Long.MAX_VALUE : byteCount.longValueExact(); }
    public int        getBytesInt()      { return byteCount.intValueExact(); }

    public BigInteger getKilobytes()     { return byteCount.divide(BYTES_IN_KB); }
    public long       getKilobytesLong() { return getKilobytes().longValueExact(); }
    public int        getKilobytesInt()  { return getKilobytes().intValueExact(); }

    public BigInteger getMegabytes()     { return byteCount.divide(BYTES_IN_MB); }
    public long       getMegabytesLong() { return getMegabytes().longValueExact(); }
    public int        getMegabytesInt()  { return getMegabytes().intValueExact(); }

    public BigInteger getGigabytes()     { return byteCount.divide(BYTES_IN_GB); }
    public long       getGigabytesLong() { return getGigabytes().longValueExact(); }
    public int        getGigabytesInt()  { return getGigabytes().intValueExact(); }

    public BigInteger getTerabytes()     { return byteCount.divide(BYTES_IN_TB); }
    public long       getTerabytesLong() { return getTerabytes().longValueExact(); }
    public int        getTerabytesInt()  { return getTerabytes().intValueExact(); }

    public BigInteger getPetabytes()     { return byteCount.divide(BYTES_IN_PB); }
    public long       getPetabytesLong() { return getPetabytes().longValueExact(); }
    public int        getPetabytesInt()  { return getPetabytes().intValueExact(); }

    public BigInteger getExabytes()      { return byteCount.divide(BYTES_IN_EB); }
    public long       getExabytesLong()  { return getExabytes().longValueExact(); }
    public int        getExabytesInt()   { return getExabytes().intValueExact(); }

    public BigInteger getZettabytes()      { return byteCount.divide(BYTES_IN_ZB); }
    public long       getZettabytesLong()  { return getZettabytes().longValueExact(); }
    public int        getZettabytesInt()   { return getZettabytes().intValueExact(); }

    public BigInteger getYottabytes()      { return byteCount.divide(BYTES_IN_YB); }
    public long       getYottabytesLong()  { return getYottabytes().longValueExact(); }
    public int        getYottabytesInt()   { return getYottabytes().intValueExact(); }

    @Override public String toString()                        { return byteCount.toString(); }
    @Override public int    compareTo(ByteSize other)         { return getBytes().compareTo(other.getBytes()); }
    @Override public int    compare(ByteSize b1, ByteSize b2) { return b1.compareTo(b2); }

    public ByteSize   plus(ByteSize size) { return new ByteSize(getBytes().add(size.getBytes())); }
    public ByteSize   plus(long bytes) { return plus(ByteSize.ofBytes(bytes)); }

    public ByteSize   minus(ByteSize size) { return new ByteSize(getBytes().subtract(size.getBytes())); }
    public ByteSize   minus(long bytes) { return minus(ByteSize.ofBytes(bytes)); }

    public boolean isZero() { return getBytesLongClipped() == 0; }

    public boolean isSmallerThan(ByteSize other) { return is(this).smallerThan(other); }
    public boolean isSmallerThan(long byteCount) { return is(this).smallerThan(ByteSize.ofBytes(byteCount)); }

    public boolean isEqualTo(ByteSize other) { return is(this).equalTo(other); }
    public boolean isEqualTo(long byteCount) { return is(this).equalTo(ByteSize.ofBytes(byteCount)); }

    public boolean isGreaterThan(ByteSize other) { return is(this).greaterThan(other); }
    public boolean isGreaterThan(long byteCount) { return is(this).greaterThan(ByteSize.ofBytes(byteCount)); }
}
