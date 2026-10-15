package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.auth.AuthServiceImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AuthSessionTest {
    @TempDir Path tmp;

    private AuthServiceImpl make(LongSupplier clock) throws Exception {
        Files.write(tmp.resolve("users.txt"), List.of(
            AuthServiceImpl.userLine("alice", "alice123".toCharArray(), "staff", "user"),
            AuthServiceImpl.userLine("bob", "bob123".toCharArray(), "staff", "user")));
        return new AuthServiceImpl(tmp.resolve("users.txt"), clock);
    }

    @Test void loginGivesTokenThatResolvesToUser() throws Exception {
        AuthServiceImpl a = make(() -> 0L);
        var s = a.login("alice", "alice123").orElseThrow();
        assertFalse(s.token().isBlank());
        assertEquals("alice", a.resolve(s.token()).orElseThrow().userId());
    }

    @Test void eachLoginGetsDifferentToken() throws Exception {
        AuthServiceImpl a = make(() -> 0L);
        String t1 = a.login("alice", "alice123").orElseThrow().token();
        String t2 = a.login("alice", "alice123").orElseThrow().token();
        assertNotEquals(t1, t2);
    }

    @Test void unknownTokenIsRejected() throws Exception {
        AuthServiceImpl a = make(() -> 0L);
        assertTrue(a.resolve("khong-co-token-nay").isEmpty());
    }

    @Test void logoutKillsOnlyThatSession() throws Exception {
        AuthServiceImpl a = make(() -> 0L);
        var s1 = a.login("alice", "alice123").orElseThrow();
        var s2 = a.login("bob", "bob123").orElseThrow();
        a.logout(s1.token());
        assertTrue(a.resolve(s1.token()).isEmpty());
        assertTrue(a.resolve(s2.token()).isPresent());
    }

    @Test void sessionExpiresAfter30MinutesIdle() throws Exception {
        long[] now = {0L};
        AuthServiceImpl a = make(() -> now[0]);
        var s = a.login("alice", "alice123").orElseThrow();
        now[0] = 29 * 60_000L;
        assertTrue(a.resolve(s.token()).isPresent());         // 29 phut: con han
        now[0] = 60 * 60_000L;                                // 31 phut ke tu lan dung cuoi
        assertTrue(a.resolve(s.token()).isEmpty());           // het han
    }

    @Test void activityExtendsSession() throws Exception {
        long[] now = {0L};
        AuthServiceImpl a = make(() -> now[0]);
        var s = a.login("alice", "alice123").orElseThrow();
        now[0] = 20 * 60_000L;
        assertTrue(a.resolve(s.token()).isPresent());         // dung token -> gia han
        now[0] = 40 * 60_000L;
        assertTrue(a.resolve(s.token()).isPresent());         // moi 20 phut ke tu lan dung cuoi
        now[0] = 71 * 60_000L;
        assertTrue(a.resolve(s.token()).isEmpty());           // 31 phut khong dung -> het han
    }
}