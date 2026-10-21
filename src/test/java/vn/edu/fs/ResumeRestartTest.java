package vn.edu.fs;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.client.FileClient;
import vn.edu.fs.common.*;
import vn.edu.fs.server.*;
import vn.edu.fs.storage.FileServiceImpl;
import vn.edu.fs.storage.LockManager;

import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.net.BindException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tiep tuc tai do khi TAT / BAT LAI SERVER, tu thu lai khi mat ket noi giua chung, so khop hash .part,
 * tai lai tu dau (restart) va khoa khi DELETE.
 */
class ResumeRestartTest {
    static final char[] PW = "changeit".toCharArray();
    @TempDir static Path ksDir;
    static Path ks;
    static SSLContext ctx;
    static final byte[] DATA = new byte[600_000];

    @BeforeAll static void init() throws Exception {
        new Random(11).nextBytes(DATA);
        ks = ksDir.resolve("server.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fs", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        ctx = TlsConfig.clientContext(ks, PW);
    }

    /** Mot server that + thu muc goc; restart() = tat han roi bat lai tren CUNG cong va CUNG thu muc (session mat, .part con). */
    static final class Env implements AutoCloseable {
        final Path work, root;
        FileServer server;
        int port, gen;

        Env(Path work) throws Exception {
            this.work = work;
            root = Files.createDirectories(work.resolve("root"));
            Files.createDirectories(root.resolve("secret"));
            start(0);
        }

        void start(int p) throws Exception {
            TestServices sv = TestServices.create(work, root);
            server = new FileServer(new FileServer.Config(p, ks, PW, 10_000_000L, 5_000, 8),
                    sv.auth, sv.acl, sv.sandbox, sv.files, new AuditLogger(work.resolve("audit" + (++gen) + ".log")));
            server.start();
            port = server.port();
        }

        void restart() throws Exception {
            int p = port;
            server.close();                                         // cat het ket noi dang mo
            for (int i = 0; ; i++) {
                try { start(p); return; }
                catch (BindException e) { if (i >= 30) throw e; Thread.sleep(200); }
            }
        }

        FileClient client() throws Exception {
            FileClient c = new FileClient("localhost", port, ctx);
            c.login("alice", "alice123");
            c.setRetryDelayMs(200);
            return c;
        }

        @Override public void close() throws Exception { server.close(); }
    }

    static void waitForPart(Path part) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (Files.exists(part) && Files.size(part) > 0) return;
            Thread.sleep(50);
        }
    }

    static String sha(byte[] b, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(b, 0, len);
        return HexFormat.of().formatHex(md.digest());
    }

    // ---------------------------------------------------------------- server tat/bat lai giua luc dang truyen

    @Test void serverRestartedMidUploadClientResumesAutomatically(@TempDir Path dir) throws Exception {
        Path src = Files.write(dir.resolve("src.bin"), DATA);
        try (Env env = new Env(dir.resolve("env")); FileClient c = env.client()) {
            boolean[] fired = {false};
            long[] seenAfterRestart = {-1};
            c.upload("/up.bin", src, (done, total) -> {
                if (!fired[0] && done > total / 3) {
                    fired[0] = true;
                    try {
                        waitForPart(env.root.resolve("up.bin.part"));   // server da nhan duoc it nhat mot phan
                        env.restart();                                  // TAT ROI BAT LAI SERVER giua luc dang tai len
                    } catch (Exception e) { throw new RuntimeException(e); }
                } else if (fired[0] && seenAfterRestart[0] < 0) {
                    seenAfterRestart[0] = done;
                }
            });
            assertTrue(c.reconnectCount() >= 1, "client phai tu ket noi lai");
            assertTrue(seenAfterRestart[0] > 0, "sau khi bat lai phai tiep tuc tu phan da co, khong phai tu 0: " + seenAfterRestart[0]);
            assertArrayEquals(DATA, Files.readAllBytes(env.root.resolve("up.bin")));
            assertFalse(Files.exists(env.root.resolve("up.bin.part")));
        }
    }

    @Test void serverRestartedMidDownloadClientResumesAutomatically(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir.resolve("env")); FileClient c = env.client()) {
            Path big = env.root.resolve("dl.bin");
            try (OutputStream o = Files.newOutputStream(big)) {
                Random r = new Random(5);
                byte[] b = new byte[1 << 20];
                for (int i = 0; i < 12; i++) { r.nextBytes(b); o.write(b); }
            }
            Path target = dir.resolve("dl-copy.bin");
            boolean[] fired = {false};
            c.download("/dl.bin", target, (done, total) -> {
                if (!fired[0] && done > total / 3) {
                    fired[0] = true;
                    try { env.restart(); } catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            assertEquals(-1, Files.mismatch(big, target));
            assertFalse(Files.exists(dir.resolve("dl-copy.bin.part")));
        }
    }

    @Test void partOnDiskSurvivesServerRestartAndOnlyRemainderIsSent(@TempDir Path dir) throws Exception {
        Path src = Files.write(dir.resolve("src.bin"), DATA);
        try (Env env = new Env(dir.resolve("env"))) {
            Files.write(env.root.resolve("keep.bin.part"), java.util.Arrays.copyOf(DATA, 250_000));   // ban dang do con lai
            env.restart();
            try (FileClient c = env.client()) {
                assertEquals(250_000, c.remotePartSize("/keep.bin"));
                long[] first = {-1};
                c.upload("/keep.bin", src, (done, total) -> { if (first[0] < 0) first[0] = done; });
                assertEquals(250_000, first[0]);
            }
            assertArrayEquals(DATA, Files.readAllBytes(env.root.resolve("keep.bin")));
        }
    }

    // ---------------------------------------------------------------- so khop .part

    @Test void remotePartReportsSizeAndSha(@TempDir Path dir) throws Exception {
        try (Env env = new Env(dir.resolve("env")); FileClient c = env.client()) {
            assertEquals(0, c.remotePart("/none.bin").size());
            Files.write(env.root.resolve("x.bin.part"), java.util.Arrays.copyOf(DATA, 70_000));
            FileClient.RemotePart rp = c.remotePart("/x.bin");
            assertEquals(70_000, rp.size());
            assertEquals(sha(DATA, 70_000), rp.sha256());
        }
    }

    @Test void stalePartIsDetectedBeforeSendingAnything(@TempDir Path dir) throws Exception {
        Path src = Files.write(dir.resolve("src.bin"), DATA);
        try (Env env = new Env(dir.resolve("env")); FileClient c = env.client()) {
            byte[] other = new byte[50_000];
            new Random(99).nextBytes(other);                        // .part cua file KHAC
            Files.write(env.root.resolve("s.bin.part"), other);
            long[] first = {-1};
            c.upload("/s.bin", src, (done, total) -> { if (first[0] < 0) first[0] = done; });
            assertTrue(first[0] > 0 && first[0] <= 32 * 1024, "phai gui lai tu dau ngay, first=" + first[0]);
            assertArrayEquals(DATA, Files.readAllBytes(env.root.resolve("s.bin")));
        }
    }

    @Test void restartFlagIgnoresValidPart(@TempDir Path dir) throws Exception {
        Path src = Files.write(dir.resolve("src.bin"), DATA);
        try (Env env = new Env(dir.resolve("env")); FileClient c = env.client()) {
            Files.write(env.root.resolve("r.bin.part"), java.util.Arrays.copyOf(DATA, 200_000));   // .part HOP LE
            long[] first = {-1};
            c.upload("/r.bin", src, (done, total) -> { if (first[0] < 0) first[0] = done; }, true);
            assertTrue(first[0] > 0 && first[0] <= 32 * 1024, "tai lai tu dau, first=" + first[0]);
            assertArrayEquals(DATA, Files.readAllBytes(env.root.resolve("r.bin")));

            Path big = env.root.resolve("d.bin");
            Files.write(big, DATA);
            Path target = dir.resolve("d-copy.bin");
            Files.write(FileClient.partFile(target), java.util.Arrays.copyOf(DATA, 100_000));
            long[] firstDl = {-1};
            c.download("/d.bin", target, (done, total) -> { if (firstDl[0] < 0) firstDl[0] = done; }, true);
            assertTrue(firstDl[0] <= 32 * 1024, "tai xuong lai tu dau, first=" + firstDl[0]);
            assertEquals(-1, Files.mismatch(big, target));
        }
    }

    // ---------------------------------------------------------------- khoa khi xoa

    @Test void deleteIsRefusedWhileFileIsLocked(@TempDir Path dir) throws Exception {
        LockManager lm = new LockManager(200);
        FileServiceImpl fs = new FileServiceImpl(lm);
        Path f = Files.write(dir.resolve("x.txt"), new byte[]{1, 2, 3});
        try (LockManager.Held h = lm.read(f)) {
            FsException e = assertThrows(FsException.class, () -> fs.delete(f));
            assertEquals(ErrorCode.LOCKED, e.code());
        }
        assertTrue(Files.exists(f));
        fs.delete(f);
        assertFalse(Files.exists(f));
    }
}
