package javaos;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The lock screen's passphrase check.
 *
 * <p>This is a desktop that pretends to be an operating system, but a stored
 * passphrase is a real one: people reuse them. So nothing here keeps the text.
 * A passphrase becomes a random salt and a PBKDF2 derivation of it, and the
 * settings file holds only those two, base64-encoded. Checking a guess derives
 * it again with the stored salt and compares in constant time.
 *
 * <p>It is not a security boundary -- anyone who can edit the settings file can
 * clear the lock -- but it costs nothing to store the one secret properly.
 */
public final class Passphrase {

    private static final int ITERATIONS = 210_000;
    private static final int BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A salt and the key derived from it, as they are written to the settings file. */
    public record Stored(String salt, String hash) {
    }

    private Passphrase() {
    }

    /** Derives a fresh salt and hash for a new passphrase. */
    public static Stored of(char[] passphrase) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(passphrase, salt);
        Stored stored = new Stored(Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash));
        Arrays.fill(hash, (byte) 0);
        return stored;
    }

    /** True when the guess derives to the stored hash under the stored salt. */
    public static boolean matches(char[] guess, Stored stored) {
        if (stored == null || stored.salt().isBlank() || stored.hash().isBlank()) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(stored.salt());
            byte[] expected = Base64.getDecoder().decode(stored.hash());
            byte[] actual = derive(guess, salt);
            boolean same = java.security.MessageDigest.isEqual(expected, actual);
            Arrays.fill(actual, (byte) 0);
            return same;
        } catch (RuntimeException e) {
            return false;       // a mangled settings file is a failed match, not a crash
        }
    }

    private static byte[] derive(char[] passphrase, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // Every JDK ships PBKDF2; if this one does not, fall back to a plain
            // digest rather than losing the ability to lock the screen at all.
            java.security.MessageDigest digest;
            try {
                digest = java.security.MessageDigest.getInstance("SHA-256");
            } catch (Exception fatal) {
                throw new IllegalStateException("No digest available", fatal);
            }
            digest.update(salt);
            return digest.digest(new String(passphrase).getBytes(StandardCharsets.UTF_8));
        } finally {
            spec.clearPassword();
        }
    }
}
