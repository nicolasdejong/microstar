package net.microstar.common.util;

import lombok.Builder.Default;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.Character.MAX_RADIX;

/** UserToken without microstar dependencies (exception Encryption) so it can be used outside MicroStar */
@RequiredArgsConstructor
@EqualsAndHashCode @ToString @SuperBuilder
public class UserTokenBase {
    public    static final Duration      DEFAULT_LIFETIME = Duration.ofDays(7);
    public    static final UserTokenBase GUEST_TOKEN      = UserTokenBase.builder().id("0").name("Guest").email("").build();
    protected static       Duration      tokenLifetime    = DEFAULT_LIFETIME;
    private   static final int           SECRET_WIDTH     = 32; // encryption key is not allowed when not 32 chars

    @Default public final String id = "";
    @Default public final String name = "";
    @Default public final String email = "";

    private static Encryption encryption = new Encryption(Encryption.Settings.builder()
        .encSalt("UserTokenSalt")
        .hashSalt("UserTokenHashSalt")
        .encAlgorithm("PBEWithHmacSHA512AndAES_256")
        .build());

    public static void setEncryption(Encryption enc) { encryption = enc; }
    public static class NotAuthorizedException extends RuntimeException {
        public NotAuthorizedException(String message) { super(message); }
    }

    /** <pre>
     * Currently the token string is optimized for speed and size.
     * Therefore, currently no json is used. The token is encrypted
     * and base64. Data is stored as Pascal strings (length followed
     * by text data).
     *
     * Current token form is:
     *
     * byte:count,count*(byte:length,length*byte)
     *
     * example: 3,5,abcde,6,abcdef,7,abcdefg  (commas added for clarity)
     *
     * Current string order:
     * expireTime -- epoch time in ms when this token expires as base MAX_RADIX
     * id         -- unique user id
     * name       -- user name
     * email      -- user email
     */

    public String toTokenString(String secret) { return toTokenString(secret, tokenLifetime); }
    public String toTokenString(String secret, Duration lifetime) {
        final long timeoutTime = System.currentTimeMillis() + lifetime.toMillis();
        return encodePartsToString(List.of(Long.toString(timeoutTime, MAX_RADIX), id, name, email), secret);
    }

    public static UserTokenBase fromTokenString(@Nullable String tokenString, String secret) { return fromTokenString(tokenString, secret, false); }
    public static UserTokenBase fromTokenString(@Nullable String tokenString, String secret, boolean ignoreExpire) {
        if(tokenString == null || tokenString.isEmpty()) return GUEST_TOKEN;
        final List<String> parts = decodeStringToParts(tokenString, secret);

        if(parts.size() < 4) throw invalidToken();
        if(!ignoreExpire && toLong(parts.get(0)) < System.currentTimeMillis()) throw expiredToken();

        return UserTokenBase.builder()
            .id   (parts.get(1))
            .name (parts.get(2))
            .email(parts.get(3))
            .build();
    }
    public static long getTokenExpire(@Nullable String tokenString, String secret) {
        if(tokenString == null || tokenString.isEmpty()) return -1;
        final List<String> parts = decodeStringToParts(tokenString, secret);
        if(parts.size() < 3) return -1;
        return toLong(parts.get(0));
    }

    private static String encodePartsToString(List<String> parts, String secret) {
        byte[] bytes;
        try {
            final ByteArrayOutputStream bout = new ByteArrayOutputStream();

            bout.write(parts.size());
            for(final String part : parts) {
                final byte[] partBytes = part.getBytes(Encryption.TEXT_ENCODING);
                bout.write(partBytes.length);
                bout.write(partBytes);
            }
            bytes = bout.toByteArray();
        } catch(final Exception e) {
            bytes = new byte[0];
        }
        return Encryption.encodeToString(encryption.encrypt(bytes, withSecretWidth(secret)));
    }
    private static List<String> decodeStringToParts(String input, String secret) {
        try {
            final byte[] encryptedBytes = Encryption.decodeFromString(input);
            final byte[] decryptedBytes = encryption.decrypt(encryptedBytes, withSecretWidth(secret));

            final ByteArrayInputStream bin = new ByteArrayInputStream(decryptedBytes);
            final int count = bin.read();
            if (count <= 0 || count > 99) throw new IllegalStateException("wrong count");
            final List<String> parts = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int len = bin.read();
                if(len > 0) {
                    final byte[] bytes = new byte[len];
                    if (len != bin.read(bytes)) throw new IllegalStateException("error reading token");
                    parts.add(new String(bytes, Encryption.TEXT_ENCODING));
                } else parts.add("");
            }
            return parts;
        } catch(final Exception cause) {
            throw invalidToken();
        }
    }

    private static long toLong(String s) {
        try { return Long.parseLong(s, MAX_RADIX); } catch(final Exception failed) { return 0L; }
    }

    private static final Map<String,String> cachedSecretWidths = new HashMap<>();
    private static String withSecretWidth(String secretToSetWidthOf) {
        final String existing = cachedSecretWidths.get(secretToSetWidthOf);
        if(existing != null) return existing;
        String s = secretToSetWidthOf;
        while(s.length() < SECRET_WIDTH) s += s; // NOSONAR -- simpler code than using a StringBuilder
        if(s.length() > SECRET_WIDTH) s = s.replace("-", "");
        if(s.length() > SECRET_WIDTH) s = s.substring(0, SECRET_WIDTH);
        if(cachedSecretWidths.size() > 100) cachedSecretWidths.clear();
        cachedSecretWidths.put(secretToSetWidthOf, s);
        return s;
    }

    private static NotAuthorizedException invalidToken() { return notAuthorized("Token invalid"); }
    private static NotAuthorizedException expiredToken() { return notAuthorized("Token expired"); }
    private static NotAuthorizedException notAuthorized(String reason) {
        return new NotAuthorizedException(reason);
    }
}
