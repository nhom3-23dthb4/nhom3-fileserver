package vn.edu.fs.common;

import java.util.Optional;

/** B trien khai. */
public interface AuthService {
    /** Dang nhap; rong neu sai tai khoan/mat khau (khong tiet lo ly do). */
    Optional<Session> login(String user, String password);
    Optional<Session> resolve(String token);
    void logout(String token);
}
