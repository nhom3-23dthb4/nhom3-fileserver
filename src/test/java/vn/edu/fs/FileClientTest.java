package vn.edu.fs;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.client.FileClient;
import vn.edu.fs.common.*;
import vn.edu.fs.server.*;

import javax.net.ssl.SSLContext;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Test D1: FileClient that noi chuyen voi server that (dung cac module that). */
class FileClientTest {
    static final char[] PW = "changeit".toCharArray();
    @TempDir static Path tmp;
    static FileServer server;
    static Path ks, root;
    static SSLContext ctx;

    @BeforeAll static void start() throws Exception {
        ks = tmp.resolve("server.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fs", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("secret"));
        TestServices sv = TestServices.create(tmp, root);
        server = new FileServer(new FileServer.Config(0, ks, PW, 10_000_000L, 5_000, 8),
                sv.auth, sv.acl, sv.sandbox, sv.files,
                new AuditLogger(tmp.resolve("audit.log")));
        server.start();
        ctx = TlsConfig.clientContext(ks, PW);
    }
    @AfterAll static void stop() throws Exception { server.close(); }

    FileClient connect() throws Exception { return new FileClient("localhost", server.port(), ctx); }

    @Test void loginWrongPasswordThrows401() throws Exception {
        try (FileClient c = connect()) {
            FsException e = assertThrows(FsException.class, () -> c.login("alice", "sai"));
            assertEquals(ErrorCode.UNAUTHORIZED, e.code());
        }
    }

    @Test void uploadDownloadListMkdirDelete() throws Exception {
        byte[] data = new byte[300_000];                       // lon hon 1 manh 32 KB nhieu lan
        new Random(1).nextBytes(data);
        Path local = tmp.resolve("local.bin"), back = tmp.resolve("back.bin");
        Files.write(local, data);
        try (FileClient c = connect()) {
            assertFalse(c.login("alice", "alice123").isBlank());   // tra ve token
            long[] last = {0};
            c.upload("/up.bin", local, (done, total) -> last[0] = done);
            assertEquals(data.length, last[0]);                    // thanh tien trinh chay den het
            c.download("/up.bin", back, ChunkListener.NONE);
            assertTrue(Arrays.equals(data, Files.readAllBytes(back)));
            assertTrue(c.list("/").stream().anyMatch(i -> i.name().equals("up.bin") && !i.directory()));
            c.mkdir("/newdir");
            assertTrue(c.list("/").stream().anyMatch(i -> i.name().equals("newdir") && i.directory()));
            c.delete("/newdir");
            c.delete("/up.bin");
            c.logout();
        }
    }

    @Test void errorsMapToErrorCodes() throws Exception {
        try (FileClient c = connect()) {
            c.login("bob", "bob123");
            Path z = tmp.resolve("z.bin");
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(FsException.class, () -> c.download("/khong-co", z, ChunkListener.NONE)).code());
            assertFalse(Files.exists(z));                          // loi thi khong de file rac
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(FsException.class, () -> c.list("/secret")).code());
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(FsException.class, () -> c.mkdir("/bob-khong-duoc-ghi")).code());
            Path up = Files.write(tmp.resolve("u.bin"), new byte[10]);
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(FsException.class, () -> c.upload("/u.bin", up, ChunkListener.NONE)).code());
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(FsException.class, () -> c.list("/../..")).code());
        }
    }
}
