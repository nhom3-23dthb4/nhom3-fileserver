package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.auth.AuthServiceImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    @TempDir Path tmp;

    private AuthServiceImpl make() throws Exception {
        Files.write(tmp.resolve("users.txt"), List.of(
            AuthServiceImpl.userLine("alice", "alice123".toCharArray(), "staff", "user"),
            AuthServiceImpl.userLine("bob", "bob123".toCharArray(), "staff", "user"),
            AuthServiceImpl.userLine("root", "root123".toCharArray(), "", "admin")));
        return new AuthServiceImpl(tmp.resolve("users.txt"));
    }

    @Test void loginRightAndWrong() throws Exception {
        AuthServiceImpl a = make();
        assertTrue(a.login("alice", "alice123").isPresent());
        assertTrue(a.login("alice", "sai").isEmpty());
        assertTrue(a.login("ghost", "x").isEmpty());          // user khong ton tai cung bi tu choi
    }

    @Test void sessionResolveAndLogout() throws Exception {
        AuthServiceImpl a = make();
        var s = a.login("alice", "alice123").orElseThrow();
        assertEquals("alice", a.resolve(s.token()).orElseThrow().userId());
        a.logout(s.token());
        assertTrue(a.resolve(s.token()).isEmpty());
    }

    @Test void lockoutAfterFiveFailures() throws Exception {
        AuthServiceImpl a = make();
        for (int i = 0; i < 5; i++) a.login("bob", "sai");
        assertTrue(a.login("bob", "bob123").isEmpty());       // dang bi khoa 60 giay
    }

    @Test void groupsAndAdmin() throws Exception {
        AuthServiceImpl a = make();
        assertTrue(a.groupsOf("alice").contains("staff"));
        assertTrue(a.isAdmin("root"));
        assertFalse(a.isAdmin("alice"));
    }
}