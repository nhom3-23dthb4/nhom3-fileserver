package vn.edu.fs;

import org.junit.jupiter.api.Test;
import vn.edu.fs.auth.PasswordHasher;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test void hashAndVerifyWork() {
        byte[] s1 = PasswordHasher.newSalt(), s2 = PasswordHasher.newSalt();
        byte[] h1 = PasswordHasher.hash("alice123".toCharArray(), s1);
        byte[] h2 = PasswordHasher.hash("alice123".toCharArray(), s2);
        assertFalse(java.util.Arrays.equals(h1, h2));                          // salt khac -> hash khac
        assertTrue(PasswordHasher.verify("alice123".toCharArray(), s1, h1)); // dung mat khau
        assertFalse(PasswordHasher.verify("sai".toCharArray(), s1, h1));    // sai mat khau
    }
}