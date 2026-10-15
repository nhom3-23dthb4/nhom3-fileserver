package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.auth.AuthServiceImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AuthLockoutTest {
    @TempDir Path tmp;

    private AuthServiceImpl make(LongSupplier clock) throws Exception {
        Files.write(tmp.resolve("users.txt"), List.of(
            AuthServiceImpl.userLine("alice", "alice123".toCharArray(), "staff", "user"),
            AuthServiceImpl.userLine("bob", "bob123".toCharArray(), "staff", "user")));
        return new AuthServiceImpl(tmp.resolve("users.txt"), clock);
    }

    @Test void lockedAfterFiveFailuresEvenWithRightPassword() throws Exception {
        AuthServiceImpl a = make(() -> 0L);
        for (int i = 0; i < 5; i++) a.login("bob", "sai");
        assertTrue(a.login("bob", "bob123").isEmpty());       // dung mat khau van bi khoa
    }

    @Test void fourFailuresDoNotLock() throws Exception {
        AuthServiceImpl a = make(() -> 0L);
        for (int i = 0; i < 4; i++) a.login("bob", "sai");
        assertTrue(a.login("bob", "bob123").isPresent());     // moi sai 4 lan -> chua khoa
    }

    @Test void lockExpiresAfter60Seconds() throws Exception {
        long[] now = {0L};
        AuthServiceImpl a = make(() -> now[0]);
        for (int i = 0; i < 5; i++) a.login("bob", "sai");
        now[0] = 59_999L;
        assertTrue(a.login("bob", "bob123").isEmpty());       // chua du 60 giay
        now[0] = 60_001L;
        assertTrue(a.login("bob", "bob123").isPresent());     // het khoa
    }

    @Test void successResetsFailCounter() throws Exception {
        AuthServiceImpl a = make(() -> 0L);
        for (int i = 0; i < 4; i++) a.login("bob", "sai");
        assertTrue(a.login("bob", "bob123").isPresent());     // dang nhap dung -> xoa bo dem
        for (int i = 0; i < 4; i++) a.login("bob", "sai");
        assertTrue(a.login("bob", "bob123").isPresent());     // van chua bi khoa
    }

    @Test void lockIsPerUser() throws Exception {
        AuthServiceImpl a = make(() -> 0L);
        for (int i = 0; i < 5; i++) a.login("bob", "sai");
        assertTrue(a.login("alice", "alice123").isPresent()); // alice khong bi anh huong
    }
}