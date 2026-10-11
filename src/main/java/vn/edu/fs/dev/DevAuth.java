package vn.edu.fs.dev;

import vn.edu.fs.common.AuthService;
import vn.edu.fs.common.Session;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** TAM THOI cua A de chay thu. B se thay bang UserStore PBKDF2. */
public final class DevAuth implements AuthService {
    private final Map<String, String> users = Map.of("alice", "alice123", "bob", "bob123");
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override public Optional<Session> login(String user, String password) {
        if (!password.equals(users.get(user))) return Optional.empty();
        Session s = new Session(UUID.randomUUID().toString(), user);
        sessions.put(s.token(), s);
        return Optional.of(s);
    }
    @Override public Optional<Session> resolve(String token) { return Optional.ofNullable(sessions.get(token)); }
    @Override public void logout(String token) { sessions.remove(token); }
}
