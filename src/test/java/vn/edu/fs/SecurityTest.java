package vn.edu.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.acl.AclServiceImpl;
import vn.edu.fs.auth.AuthServiceImpl;
import vn.edu.fs.client.FileClient;
import vn.edu.fs.common.ErrorCode;
import vn.edu.fs.common.Frame;
import vn.edu.fs.common.FsException;
import vn.edu.fs.server.AuditLogger;
import vn.edu.fs.server.FileServer;
import vn.edu.fs.server.TlsConfig;
import vn.edu.fs.storage.FileServiceImpl;
import vn.edu.fs.storage.LockManager;
import vn.edu.fs.storage.PathSandboxImpl;

/**
 * Test bảo mật trên server THẬT (dựng giống EndToEndTest): (1) chỉ chấp nhận TLS 1.3,
 * (2) khóa tạm sau nhiều lần đăng nhập sai, (3) khung dữ liệu quá lớn bị ngắt mà server vẫn sống.
 */
class SecurityTest {

    static final char[] PW = "changeit".toCharArray();

    @TempDir static Path tmp;
    static Path keystore;
    static FileServer server;
    static int port;

    @BeforeAll
    static void startServer() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("public"));

        // alice: dùng cho kiểm tra "server vẫn sống"; locktest: CHỈ dùng cho test khóa tạm
        Path users = tmp.resolve("users.tsv");
        Files.write(users, List.of(
                AuthServiceImpl.userLine("alice", "alice123".toCharArray(), "staff", "user"),
                AuthServiceImpl.userLine("locktest", "Matkhau-dung-1".toCharArray(), "staff", "user")));
        AuthServiceImpl auth = new AuthServiceImpl(users);

        Path aclFile = tmp.resolve("acl.tsv");
        Files.write(aclFile, List.of("/public\tgroup:staff\tALLOW\tLIST,READ,WRITE,DELETE"));
        AclServiceImpl acl = new AclServiceImpl(AclServiceImpl.load(aclFile), auth);

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

    /** SSLContext chỉ dùng giao thức cần thử, TIN đúng chứng chỉ của server như FileClient. */
    private static SSLContext contextFor(String protocol) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) { ts.load(in, PW); }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance(protocol);
        ctx.init(null, tmf.getTrustManagers(), null);   // KHÔNG dùng TrustManager "tin tất cả"
        return ctx;
    }

    private static FileClient connect() throws Exception {
        return new FileClient("localhost", port, TlsConfig.clientContext(keystore, PW));
    }

    @Test
    void tls12ClientIsRejected() throws Exception {
        SSLContext ctx = contextFor("TLSv1.2");
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("localhost", port)) {
            s.setEnabledProtocols(new String[]{"TLSv1.2"});
            SSLException e = assertThrows(SSLException.class, s::startHandshake);
            // Chứng chỉ đã được tin nên lỗi phải do GIAO THỨC, không phải do chứng chỉ
            String m = String.valueOf(e.getMessage()).toLowerCase();
            assertTrue(m.contains("protocol") || m.contains("handshake"), "Lý do khác: " + m);
        }
    }

    @Test
    void tls13ClientIsAccepted() throws Exception {
        SSLContext ctx = contextFor("TLSv1.3");
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("localhost", port)) {
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
            s.startHandshake();
            assertEquals("TLSv1.3", s.getSession().getProtocol());
        }
    }

    @Test
    void accountIsLockedAfterManyWrongPasswords() throws Exception {
        for (int i = 0; i < 10; i++) {
            try (FileClient c = connect()) {
                FsException e = assertThrows(FsException.class, () -> c.login("locktest", "sai"));
                assertEquals(ErrorCode.UNAUTHORIZED, e.code());
            }
        }
        // Mật khẩu ĐÚNG nhưng tài khoản đang bị khóa tạm -> vẫn bị từ chối
        try (FileClient c = connect()) {
            FsException e = assertThrows(FsException.class, () -> c.login("locktest", "Matkhau-dung-1"));
            assertEquals(ErrorCode.UNAUTHORIZED, e.code());
        }
    }

    @Test
    void oversizedFrameIsDroppedAndServerSurvives() throws Exception {
        SSLContext ctx = contextFor("TLSv1.3");
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("localhost", port)) {
            s.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.writeByte(Frame.CONTROL);
            out.writeInt(1_000_000);                  // độ dài vượt Frame.MAX_PAYLOAD (65536)
            out.flush();

            // Server có thể kịp gửi một khung "ERR 400" trước khi đóng; sau đó PHẢI đóng kết nối.
            int first = readOrEof(in);
            if (first != -1) {
                assertEquals(Frame.CONTROL, first);
                int len = in.readInt();
                byte[] payload = new byte[len];
                in.readFully(payload);
                assertTrue(new String(payload, StandardCharsets.UTF_8).startsWith("ERR\t400"));
                assertEquals(-1, readOrEof(in), "Server phải đóng kết nối sau khi báo lỗi");
            }
        }
        // Server vẫn phục vụ người khác bình thường
        try (FileClient c = connect()) {
            c.login("alice", "alice123");
            assertNotNull(c.list("/public"));
        }
    }

    /** Đọc một byte; reset/đóng kết nối cũng coi là bị ngắt (-1). */
    private static int readOrEof(DataInputStream in) {
        try {
            return in.read();
        } catch (IOException ex) {
            return -1;
        }
    }
}
