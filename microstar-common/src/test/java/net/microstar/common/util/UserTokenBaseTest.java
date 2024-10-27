package net.microstar.common.util;

import net.microstar.testing.FlakyTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.LongStream;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserTokenBaseTest {
    private static final String TEST_ID = "12345";
    private static final String TEST_USER = "some, user, name";
    private static final String TEST_EMAIL = "some.user.name@cetera.nl";
    private static final String CLUSTER_SECRET = UUID.randomUUID().toString();

    @AfterAll
    static void tearDown() {
        UserTokenBase.tokenLifetime = UserTokenBase.DEFAULT_LIFETIME;
    }

    private static UserTokenBase createToken() {
        return UserTokenBase.builder()
            .id(TEST_ID)
            .name(TEST_USER)
            .email(TEST_EMAIL)
            .build();
    }

    @FlakyTest("Time condition so may fail on slow machines")
    @Test void decryptionShouldBeFast() {
        // It doesn't matter if creating the tokenString is expensive.
        // Creating the token from the string happens every request so should be fast.
        final UserTokenBase token = createToken();
        final String tokenString = token.toTokenString(CLUSTER_SECRET);
        final long[] times = new long[100];

        final long t00 = System.currentTimeMillis();
        for(int i=0; i<times.length; i++) {
            final long t0 = System.nanoTime();

            UserTokenBase.fromTokenString(tokenString, CLUSTER_SECRET);

            final long t1 = System.nanoTime();
            times[i] = (int)(t1 - t0);
        }
        final double avg = LongStream.of(times).average().orElseThrow();
        System.out.println("avg: " + avg + " ns" + " (" + (avg / 1000) + " us) (" + (avg / 1_000_000) + " ms)");
        assertThat((int)LongStream.of(times).average().orElseThrow(), is(lessThan(1_000_000))); // < 1ms, should be more like < 0.5ms

        // On my M1 Mac the average time for fromTokenString() is:
        //  encryption       time  init-text                    strength
        //  none             90us                               none
        //  SHA1          21000us  PBKDF2WithHmacSHA1           very strong -- apparently PBKDF is designed to be slow
        //  SHA512        18000us  PBKDF2WithHmacSHA512         very strong
        //  RC4             200us  PBEWithSHA1AndRC4_128        weak
        //  AES             210us  PBEWithHmacSHA1AndAES_128    strong
        //  AES             210us  PBEWithHmacSHA1AndAES_256    strong
        //  AES             210us  PBEWithHmacSHA512AndAES_256  strong  <-- this one seems to have a good balance of strength & speed
    }

    @Test void shouldBeAbleToSerializeFromAndToTokenString() {
        final UserTokenBase token = createToken();
        final String tokenString = token.toTokenString(CLUSTER_SECRET);
        final UserTokenBase token2 = UserTokenBase.fromTokenString(tokenString, CLUSTER_SECRET);
        assertThat(token2.id, is(token.id));
        assertThat(token2.name, is(token.name));
        assertThat(token2.email, is(token.email));
    }

    @Test void tokenShouldNotValidateAfterTimeout() {
        UserTokenBase.tokenLifetime = Duration.ofMillis(100); // for testing timeout -- may fail on very slow machines
        final UserTokenBase token = createToken();
        final String tokenString = token.toTokenString(CLUSTER_SECRET);
        assertDoesNotThrow(() -> UserTokenBase.fromTokenString(tokenString, CLUSTER_SECRET));
        sleep(101);
        assertThrows(UserTokenBase.NotAuthorizedException.class, () -> UserTokenBase.fromTokenString(tokenString, CLUSTER_SECRET));
    }

    @Test void tokenShouldNotValidateWithWrongSecret() {
        final UserTokenBase token = createToken();
        final String tokenString = token.toTokenString(CLUSTER_SECRET);
        assertThrows(UserTokenBase.NotAuthorizedException.class, () -> UserTokenBase.fromTokenString(tokenString, "wrongSecret"));
    }
}