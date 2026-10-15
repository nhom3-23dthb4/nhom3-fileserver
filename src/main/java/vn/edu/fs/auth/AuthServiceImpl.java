package vn.edu.fs.auth;

import vn.edu.fs.acl.UserDirectory;
import vn.edu.fs.common.AuthService;
import vn.edu.fs.common.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dang nhap + phien. File nguoi dung (moi dong, ngan cach bang TAB):
 *   id  saltBase64  hashBase64  nhom1,nhom2  role(user|admin)
 */
public final class AuthServiceImpl implements AuthService, UserDirectory {
    private record User(String id, byte[] salt, byte[] hash, Set<String> groups, String role) {}
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 60_000;
    private static final long IDLE_MS = 30 * 60_000L;

    private final Map<String, User> users = new HashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();     // user -> {soLanSai}
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AuthServiceImpl(Path usersFile) throws IOException {
        for (String line : Files.readAllLines(usersFile, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            Set<String> groups = f[3].isBlank() ? Set.of() : new HashSet<>(Arrays.asList(f[3].split(",")));
            users.put(f[0], new User(f[0], Base64.getDecoder().decode(f[1]),
                    Base64.getDecoder().decode(f[2]), groups, f[4]));
        }
    }

    /** Sinh mot dong de dan vao file nguoi dung (dung khi them user moi). */
    public static String userLine(String id, char[] password, String groups, String role) {
        byte[] salt = PasswordHasher.newSalt();
        return String.join("\t", id, Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(PasswordHasher.hash(password, salt)), groups, role);
    }

    @Override public Optional<Session> login(String userId, String password) {
        long now = System.currentTimeMillis();
        if (lockedUntil.getOrDefault(userId, 0L) > now) return Optional.empty();   // dang bi khoa tam
        User u = users.get(userId);
        char[] pw = password.toCharArray();
        boolean ok;
        if (u == null) { PasswordHasher.hash(pw, new byte[16]); ok = false; }       // ton thoi gian nhu that
        else ok = PasswordHasher.verify(pw, u.salt(), u.hash());
        Arrays.fill(pw, '\0');
        if (!ok) {
            int[] c = fails.computeIfAbsent(userId, k -> new int[1]);
            if (++c[0] >= MAX_FAILS) { lockedUntil.put(userId, now + LOCK_MS); c[0] = 0; }
            return Optional.empty();
        }
        fails.remove(userId);
        byte[] t = new byte[32];
        random.nextBytes(t);
        Session s = new Session(Base64.getUrlEncoder().withoutPadding().encodeToString(t), userId);
        sessions.put(s.token(), s);
        lastSeen.put(s.token(), now);
        return Optional.of(s);
    }

    @Override public Optional<Session> resolve(String token) {
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        long now = System.currentTimeMillis();
        if (now - lastSeen.getOrDefault(token, 0L) > IDLE_MS) { logout(token); return Optional.empty(); }
        lastSeen.put(token, now);
        return Optional.of(s);
    }

    @Override public void logout(String token) { sessions.remove(token); lastSeen.remove(token); }

    @Override public Set<String> groupsOf(String userId) {
        User u = users.get(userId);
        return u == null ? Set.of() : u.groups();
    }
    @Override public boolean isAdmin(String userId) {
        User u = users.get(userId);
        return u != null && u.role().equals("admin");
    }
}