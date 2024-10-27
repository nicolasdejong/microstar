package net.microstar.common.util;

import lombok.Builder;
import lombok.Builder.Default;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/** Encryption methods. Thread safe.
  *
  * @author Nicolas de Jong
  */
public class Encryption {
    public static final Charset TEXT_ENCODING = StandardCharsets.UTF_8;
    public static final int DEFAULT_MIN_PASSWORD_LENGTH = 8;
    public static final int DEFAULT_MAX_PASSWORD_LENGTH = 8;

    static {
        // Remove the VM limit on encryption complexity.
        // Since JDK 9, apparently setting a property is enough.
        // Since JDK 11, using reflection to set a final boolean to false no longer works.

        // https://www.taithienbo.com/encryption-in-java-with-jca-and-bouncy-castle-api/
        Security.setProperty("crypto.policy", "unlimited");

        try {
            if(Cipher.getMaxAllowedKeyLength("AES") < 1000) {
                //noinspection UseOfSystemOutOrSystemErr
                System.out.println("LIMITED cipher keys!"); // NOSONAR -- once, low level
            }
        } catch (final NoSuchAlgorithmException ignored) {
            // only informational
        }
    }

    @Builder(toBuilder = true) // no @ToString as that may lead to secrets ending up in the logging
    public static final class Settings {
        private static final String DEFAULT_ENC_TYPE = "AES";
        private static final String DEFAULT_ENC_CIPHER_TYPE = DEFAULT_ENC_TYPE + "/CBC/PKCS5Padding";

        // NOTE: Changing any of these settings will make it impossible to decrypt data
        //       that was encrypted with previous settings. Defaults are in constructor
        @Default public final String encAlgorithm   = "PBKDF2WithHmacSHA1";
        @Default public final String encKeyType     = DEFAULT_ENC_TYPE;
        @Default public final String encCipherType  = DEFAULT_ENC_CIPHER_TYPE;
        @Default public final int    encIterations  = 10_000;
        @Default public final int    encKeyLength   = 256;  // 256 needs removeCryptographyRestrictions(), unlike 128
        @Default public final String hashAlgorithm  = "SHA-512"; // SHA-1 gives shorter hashes
        @Default public final int    hashIterations = 30_0000;
        @Default public final int    hashKeyLength  = 128;
        @Default public final String encSalt        = "MicroStarEncSalt";
        @Default public final String hashSalt       = "MicroStarHashSalt";
    }

    private final Settings settings;
    private final IvParameterSpec encIvTmp;
    private final IvParameterSpec encIv;
    private final byte[] encSaltBytes;

    public Encryption(Encryption.Settings settings) {
        this.settings = settings;

        encIvTmp     = generateEncInitializationVector(true, settings.encCipherType); // DIFFERENT FOR EACH RUN
        encIv        = generateEncInitializationVector(false, settings.encCipherType); // THE SAME FOR EACH RUN
        encSaltBytes = settings.encSalt.getBytes(TEXT_ENCODING);
    }

    private static IvParameterSpec generateEncInitializationVector(boolean temp, String encCipherType) {
        try {
            final Cipher cipher = Cipher.getInstance( encCipherType );
            final byte[] iv = new byte[cipher.getBlockSize()];
            if( temp ) {
                new SecureRandom().nextBytes(iv);
            } else {
                // non-temp encryption vector should always be the same.
                final byte[] name = "microstar.common.util.Encryption".getBytes(TEXT_ENCODING); // hard coded as changing this breaks existing encryption
                for(int i=0; i<name.length; i++) iv[i%iv.length] ^= name[i];
            }
            return new IvParameterSpec(iv);
        } catch (final NoSuchAlgorithmException | NoSuchPaddingException cause) {
            throw new IllegalArgumentException("Failed to use encCipherType: " + encCipherType, cause);
        }
    }

    public String hashToString(String data, String password) {
        return encodeToString( hash(data, password) );
    }
    public String hashToString(String data) {
        return encodeToString( hash(data) );
    }

    public byte[] hash(String data, String password) {
        return hash(String.join("/", password, data));
    }

    public byte[] hash(String data) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(settings.hashAlgorithm);
            return digest.digest((settings.hashSalt + data).getBytes(TEXT_ENCODING));
        } catch(final NoSuchAlgorithmException cause) {
            throw new IllegalStateException(cause);
        }
    }

    public String encrypt(String data, String password) {
        return encodeToString( encrypt(data.getBytes(TEXT_ENCODING), password ) );
    }

    public byte[] encrypt(byte[] data, String password) {
        return runCipher( Cipher.ENCRYPT_MODE, data, password, encIv);
    }

    public String decrypt(String data, String password) {
        return new String( decrypt( decodeFromString( data ), password ), TEXT_ENCODING );
    }

    public byte[] decrypt(byte[] data, String password) {
        return runCipher( Cipher.DECRYPT_MODE, data, password, encIv);
    }

    /** Note that encrypted data is not compatible between VM runs (iv is generated anew each time) */
    public String encryptTmp(String data, String password) {
        return encodeToString( encryptTmp(data.getBytes(TEXT_ENCODING), password ) );
    }

    /** Note that encrypted data is not compatible between VM runs (iv is generated anew each time) */
    public byte[] encryptTmp(byte[] data, String password) {
        return runCipher( Cipher.ENCRYPT_MODE, data, password, encIvTmp);
    }

    /** Note that encrypted data is not compatible between VM runs (iv is generated anew each time) */
    public String decryptTmp(String data, String password) {
        return new String( decryptTmp( decodeFromString( data ), password ), TEXT_ENCODING );
    }

    /** Note that encrypted data is not compatible between VM runs (iv is generated anew each time) */
    public byte[] decryptTmp(byte[] data, String password) {
        return runCipher( Cipher.DECRYPT_MODE, data, password, encIvTmp);
    }

    private byte[] runCipher(int opMode, byte[] data, String password, AlgorithmParameterSpec aps) {
        try {
            final Cipher cipher = Cipher.getInstance( settings.encCipherType );
            cipher.init(opMode, getSecretKey(password), aps);
            return cipher.doFinal(data);
        } catch(final NoSuchAlgorithmException
                      | NoSuchPaddingException
                      | InvalidKeyException
                      | InvalidAlgorithmParameterException
                      | InvalidKeySpecException
                      | IllegalBlockSizeException
                      | BadPaddingException cause) {
            throw wrongDataException(cause);
        }
    }

    public OutputStream getEncryptionOutputStream(OutputStream out, String password) {
        try {
            final Cipher cipher = Cipher.getInstance( settings.encCipherType );
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(password), encIv);
            return new CipherOutputStream(out, cipher);
        } catch(final NoSuchAlgorithmException
                      | NoSuchPaddingException
                      | InvalidKeyException
                      | InvalidAlgorithmParameterException
                      | InvalidKeySpecException cause) {
            throw wrongDataException(cause);
        }
    }
    public InputStream getDecryptionInputStream(InputStream in, String password) {
        try {
            final Cipher cipher = Cipher.getInstance( settings.encCipherType );
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(password), encIv);
            return new CipherInputStream(in, cipher);
        } catch(final NoSuchAlgorithmException
                      | NoSuchPaddingException
                      | InvalidKeyException
                      | InvalidAlgorithmParameterException
                      | InvalidKeySpecException cause) {
            throw wrongDataException(cause);
        }
    }

    private static IllegalArgumentException wrongDataException(Exception cause) {
        return new IllegalArgumentException("Wrong data: " + cause.getMessage());
    }

    private SecretKey getSecretKey(String password) throws NoSuchAlgorithmException, InvalidKeySpecException {
        // Some algorithms only support 32 characters as password.
        // Often the password is a UUID. A UUID without dashes is 32 characters.
        // Otherwise, creating the key will throw. Allowed length depends on algorithm.
        final String usePassword       = password.replace("-","");
        final SecretKeyFactory factory = SecretKeyFactory.getInstance( settings.encAlgorithm );
        final KeySpec spec             = new PBEKeySpec(usePassword.toCharArray(), encSaltBytes, settings.encIterations, settings.encKeyLength);
        final SecretKey tmp            = factory.generateSecret(spec);
        return                           new SecretKeySpec(tmp.getEncoded(), settings.encKeyType );
    }

    public static String encodeToString(byte[] data) {
        return new String(Base64.getUrlEncoder().encode(data), TEXT_ENCODING);
    }
    public static byte[] decodeFromString(String text) {
        return Base64.getUrlDecoder().decode(text.getBytes(TEXT_ENCODING));
    }

    public static String generatePassword() {
        return getRandomString( DEFAULT_MIN_PASSWORD_LENGTH, DEFAULT_MAX_PASSWORD_LENGTH );
    }
    public static String getRandomString(int minLength, int maxLength) {
        final Random random = new SecureRandom();
        final int length = Math.abs(minLength) + (maxLength > minLength ? random.nextInt(maxLength - minLength) : 0);
        final String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        final StringBuilder sb = new StringBuilder();
        for(int i=0; i<length; i++) sb.append(chars.charAt( random.nextInt(chars.length()) ));
        return sb.toString();
    }

    @SuppressWarnings("MethodWithMultipleLoops") // simple loops
    public static List<String> getAlgorithmsAsText() {
        final List<String> list = new ArrayList<>();
        for (final Provider provider : Security.getProviders()) {
            for (final Provider.Service service : provider.getServices()) {
                list.add(provider.getName() + ": " + service.getAlgorithm());
            }
        }
        list.sort(String::compareTo);
        return list;
    }
}
