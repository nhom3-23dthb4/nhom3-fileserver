package vn.edu.fs;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.client.FileClient;
import vn.edu.fs.common.*;
import vn.edu.fs.server.*;

import javax.net.ssl.SSLContext;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** Client tu ket noi lai va dang nhap lai khi server cat ket noi nhan roi (idle timeout). */
class FileClientReconnectTest {
    static final char[] PW = "changeit".toCharArray();
    static final int IDLE_MS = 1_000;                              // server cat sau 1 giay de test chay nhanh
    @TempDir static Path tmp;
    static FileServer server;
    static SSLContext ctx;

    @BeforeAll static void start() throws Exception {
        Path ks = tmp.resolve("server.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fs", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("secret"));
        TestServices sv = TestServices.create(tmp, root);
        server = new FileServer(new FileServer.Config(0, ks, PW, 10_000_000L, IDLE_MS, 8),
                sv.auth, sv.acl, sv.sandbox, sv.files, new AuditLogger(tmp.resolve("audit.log")));
        server.start();
        ctx = TlsConfig.clientContext(ks, PW);
    }
    @AfterAll static void stop() throws Exception { server.close(); }

    @Test void listWorksAfterServerDroppedIdleConnection() throws Exception {
        try (FileClient c = new FileClient("localhost", server.port(), ctx)) {
            c.login("alice", "alice123");
            assertEquals(0, c.reconnectCount());
            Thread.sleep(IDLE_MS * 3L);                            // server da cat ket noi
            assertNotNull(c.list("/"));                            // van chay: tu noi lai + dang nhap lai
            assertEquals(1, c.reconnectCount());
        }
    }

    @Test void uploadWorksAfterIdleDrop() throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "hello");
        try (FileClient c = new FileClient("localhost", server.port(), ctx)) {
            c.login("alice", "alice123");
            Thread.sleep(IDLE_MS * 3L);
            c.upload("/a.txt", f, ChunkListener.NONE);
            assertTrue(c.list("/").stream().anyMatch(i -> i.name().equals("a.txt")));
            assertEquals(1, c.reconnectCount());
        }
    }

    @Test void businessErrorsAreNotRetried() throws Exception {
        try (FileClient c = new FileClient("localhost", server.port(), ctx)) {
            c.login("bob", "bob123");
            FsException e = assertThrows(FsException.class, () -> c.list("/secret"));
            assertEquals(ErrorCode.FORBIDDEN, e.code());
            assertEquals(0, c.reconnectCount());                   // loi 403 khong gay ket noi lai
        }
    }

    @Test void noAutoLoginAfterLogout() throws Exception {
        FileClient c = new FileClient("localhost", server.port(), ctx);
        c.login("alice", "alice123");
        c.logout();
        Thread.sleep(IDLE_MS * 3L);
        assertThrows(java.io.IOException.class, () -> c.list("/"));
        assertEquals(0, c.reconnectCount());
        c.close();
    }
}