package vn.edu.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.acl.AclServiceImpl;
import vn.edu.fs.auth.AuthServiceImpl;
import vn.edu.fs.client.FileClient;
import vn.edu.fs.common.ErrorCode;
import vn.edu.fs.common.FsException;
import vn.edu.fs.server.AuditLogger;
import vn.edu.fs.server.FileServer;
import vn.edu.fs.server.TlsConfig;
import vn.edu.fs.storage.FileServiceImpl;
import vn.edu.fs.storage.LockManager;
import vn.edu.fs.storage.PathSandboxImpl;

/**
 * Test tích hợp đầu-cuối: server THẬT (TLS 1.3 + AuthServiceImpl + AclServiceImpl + PathSandboxImpl
 * + FileServiceImpl) trên cổng do hệ điều hành chọn, dùng FileClient thật như người dùng thật.
 */
class EndToEndTest {

    static final char[] PW = "changeit".toCharArray();

    @TempDir static Path tmp;
    static Path keystore;
    static Path root;
    static FileServer server;
    static int port;

    @BeforeAll
    static void startServer() throws Exception {
        // (1) Thư mục dữ liệu: /public cho nhóm staff, /secret bị chặn
        root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("public"));
        Files.createDirectories(root.resolve("secret"));
        Files.writeString(root.resolve("secret").resolve("mat.txt"), "bi mat");
        Files.writeString(tmp.resolve("ngoai-root.txt"), "file nam NGOAI thu muc goc");

        // (2) Tài khoản (mật khẩu được băm bằng PasswordHasher bên trong userLine)
        Path users = tmp.resolve("users.tsv");
        Files.write(users, List.of(
                AuthServiceImpl.userLine("alice", "alice123".toCharArray(), "staff", "user"),
                AuthServiceImpl.userLine("admin", "admin123".toCharArray(), "", "admin")));
        AuthServiceImpl auth = new AuthServiceImpl(users);

        // (3) Luật ACL: staff đọc/ghi /public, bị DENY ở /secret; admin được toàn quyền (do role)
        Path aclFile = tmp.resolve("acl.tsv");
        Files.write(aclFile, List.of(
                "/public\tgroup:staff\tALLOW\tLIST,READ,WRITE,DELETE",
                "/secret\tgroup:staff\tDENY\tLIST,READ,WRITE,DELETE"));
        AclServiceImpl acl = new AclServiceImpl(AclServiceImpl.load(aclFile), auth);

        // (4) Server có TLS 1.3 trên cổng 0 (hệ điều hành cấp cổng trống)
        keystore = tmp.resolve("server.p12");
        makeKeystore(keystore);
        server = new FileServer(
                new FileServer.Config(0, keystore, PW, 10_000_000L, 10_000, 8),
                auth, acl, new PathSandboxImpl(root), new FileServiceImpl(new LockManager()),
                new AuditLogger(tmp.resolve("audit.log")));
        server.start();
        port = server.port();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.close();
    }

    /** Sinh keystore tự ký bằng keytool của chính JDK đang chạy test (không phụ thuộc PATH). */
    private static void makeKeystore(Path ks) throws Exception {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path keytool = bin.resolve("keytool.exe");
        if (!Files.exists(keytool)) keytool = bin.resolve("keytool");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fs",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "keytool lỗi: " + out);
    }

    /** Tạo client mới đã nối TLS (chưa login), tin đúng chứng chỉ của server. */
    private static FileClient connect() throws Exception {
        SSLContext ctx = TlsConfig.clientContext(keystore, PW);
        return new FileClient("localhost", port, ctx);
    }

    private static void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        FsException e = assertThrows(FsException.class, action);
        assertEquals(expected, e.code());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        try (FileClient c = connect()) {
            assertCode(ErrorCode.UNAUTHORIZED, () -> c.login("alice", "sai-mat-khau"));
        }
    }

    @Test
    void correctLoginThenLogout() throws Exception {
        try (FileClient c = connect()) {
            String token = c.login("alice", "alice123");
            assertNotNull(token);
            assertFalse(token.isBlank());
            assertDoesNotThrow(c::logout);
        }
    }

    @Test
    void uploadThenDownloadGivesSameBytes() throws Exception {
        byte[] original = new byte[300_000];
        new Random(1).nextBytes(original);
        Path src = Files.write(tmp.resolve("goc-a.bin"), original);
        Path dst = tmp.resolve("tai-ve-a.bin");
        try (FileClient c = connect()) {
            c.login("alice", "alice123");
            c.upload("/public/a.bin", src, (done, total) -> { });
            c.download("/public/a.bin", dst, (done, total) -> { });
        }
        assertArrayEquals(original, Files.readAllBytes(dst));
    }

    @Test
    void deniedFolderGives403() throws Exception {
        try (FileClient c = connect()) {
            c.login("alice", "alice123");
            assertCode(ErrorCode.FORBIDDEN, () -> c.list("/secret"));
            assertCode(ErrorCode.FORBIDDEN, () -> c.download("/secret/mat.txt", tmp.resolve("lay-mat.txt"), (d, t) -> { }));
        }
        assertFalse(Files.exists(tmp.resolve("lay-mat.txt")));
    }

    @Test
    void pathTraversalIsBlocked() throws Exception {
        // Hợp đồng của project: đường dẫn thoát khỏi sandbox -> PathEscapeException -> mã 400 (BAD_REQUEST)
        Path leaked = tmp.resolve("lay-duoc.txt");
        try (FileClient c = connect()) {
            c.login("alice", "alice123");
            assertCode(ErrorCode.BAD_REQUEST, () -> c.list("../../etc/passwd"));
            assertCode(ErrorCode.BAD_REQUEST, () -> c.list("/public/../../x"));
            assertCode(ErrorCode.BAD_REQUEST, () -> c.download("/../ngoai-root.txt", leaked, (d, t) -> { }));
            assertCode(ErrorCode.BAD_REQUEST, () -> c.download("/public/../../ngoai-root.txt", leaked, (d, t) -> { }));
        }
        assertFalse(Files.exists(leaked), "không được đọc file nằm ngoài thư mục gốc");
    }

    @Test
    void missingFileGives404() throws Exception {
        try (FileClient c = connect()) {
            c.login("alice", "alice123");
            assertCode(ErrorCode.NOT_FOUND,
                    () -> c.download("/public/khong-co.txt", tmp.resolve("khong-co.out"), (d, t) -> { }));
        }
    }

    @Test
    void commandsBeforeLoginAreRejected() throws Exception {
        try (FileClient c = connect()) {
            assertCode(ErrorCode.UNAUTHORIZED, () -> c.list("/public"));
        }
    }
}
