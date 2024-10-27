package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;


class EncryptionTest {
    private static final String payload        = "abcFooBar1234;test-data-payload-to-hash/encrypt";
    private static final String LONG_PASSWORD  = "foobar1234:very-long-password";
    private static final String SHORT_PASSWORD = "foobar";
    private static final Encryption enc = new Encryption( Encryption.Settings.builder().build() );

    @Test void testEncSettings() {
        assertThat(Encryption.Settings.builder().encCipherType("abc").build().encCipherType, is("abc"));

        // invalid hash algorithm
        assertThrows(Exception.class, () -> new Encryption(Encryption.Settings.builder()
            .hashAlgorithm("invalid").build())
            .hash("a", "b"));
    }

    @Test void testAlgorithmsAsText() {
        final List<String> algorithms = Encryption.getAlgorithmsAsText();
        assertThat(algorithms.size(), greaterThan(0));
        // You want to print this when getting the error that an algorithm doesn't exist
        //System.out.println(String.join("\n", algorithms))
    }

    @Test void testHashToString() {
        final String hash = enc.hashToString(payload, SHORT_PASSWORD);
        assertFalse( hash.isEmpty() );
        assertThat( hash, is(not(payload)) );
        assertThat( hash, is(not(Base64.getUrlEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))) ) );

        final String hash2 = enc.hashToString(payload + "+more data", SHORT_PASSWORD);
        assertThat(hash2, is(not(hash)));
    }

    @Test void testEncrypt() {
        final String encrypted = enc.encrypt( payload, LONG_PASSWORD );
        final String decrypted = enc.decrypt( encrypted, LONG_PASSWORD );
        assertThat( decrypted, is(payload) );

        final String encryptedShort = enc.encrypt( payload, SHORT_PASSWORD );
        final String decryptedShort = enc.decrypt( encryptedShort, SHORT_PASSWORD );
        assertThat( decryptedShort, is(payload) );
    }

    @Test void testEncryptTmp() {
        final String encrypted = enc.encryptTmp( payload, LONG_PASSWORD );
        final String decrypted = enc.decryptTmp( encrypted, LONG_PASSWORD );
        assertThat( decrypted, is(payload) );

        final String encryptedShort = enc.encryptTmp( payload, SHORT_PASSWORD );
        final String decryptedShort = enc.decryptTmp( encryptedShort, SHORT_PASSWORD );
        assertThat( decryptedShort, is(payload) );
    }

    @Test void testEncryptTmpWrongPassword() {
        final String encrypted = enc.encryptTmp( payload, LONG_PASSWORD );
        assertThrows(IllegalArgumentException.class, () -> enc.decryptTmp( encrypted, "wrong password" ));
    }

    @Test void testGeneratePassword() {
        final Set<String> passwords= new HashSet<>();
        for(int i=0; i<100; i++) {
            final String pw = Encryption.generatePassword();
            assertThat(pw.length(), is(greaterThanOrEqualTo(8)));
            assertThat(pw.length(), is(lessThanOrEqualTo(12)));
            assertThat(passwords, not(contains(pw)));
            passwords.add(pw);
        }
    }
}
