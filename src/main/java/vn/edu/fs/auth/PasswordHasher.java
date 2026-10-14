package vn.edu.fs.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

public class PasswordHasher {
    private static final int SALT_LENGTH = 16;
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RAND = new SecureRandom();

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RAND.nextBytes(salt);
        return salt;
    }

    public static byte[] hash(char[] password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean verify(char[] password, byte[] salt, byte[] expectedHash) {
        byte[] actualHash = hash(password, salt);
        try {
            return Arrays.equals(actualHash, expectedHash);
        } finally {
            Arrays.fill(actualHash, (byte) 0);
        }
    }
}