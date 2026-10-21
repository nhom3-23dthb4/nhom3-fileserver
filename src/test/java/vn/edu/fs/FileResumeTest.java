package vn.edu.fs;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.client.FileClient;
import vn.edu.fs.common.*;
import vn.edu.fs.server.*;
import vn.edu.fs.storage.FileServiceImpl;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Tiep tuc tai len / tai xuong dang do (resume): offset trong READ/WRITE + lenh PARTSIZE + giu .part. */
class FileResumeTest {
    static final char[] PW = "changeit".toCharArray();
    @TempDir static Path tmp;
    static FileServer server;
    static Path root;
    static SSLContext ctx;
    static final byte[] DATA = new byte[600_000];               // ~19 khung 32 KB
    static String shaOfData;

    @BeforeAll static void start() throws Exception {
        new Random(7).nextBytes(DATA);
        shaOfData = sha(DATA);
        Path ks = tmp.resolve("server.p12");
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
                sv.auth, sv.acl, sv.sandbox, sv.files, new AuditLogger(tmp.resolve("audit.log")));
        server.start();
        ctx = TlsConfig.clientContext(ks, PW);
    }
    @AfterAll static void stop() throws Exception { server.close(); }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
    static InputStream bytes(byte[] b, int from, int to) { return new ByteArrayInputStream(b, from, to - from); }

    /** Stream giong mang bi dut: giao {@code limit} byte roi nem IOException. */
    static InputStream dropAfter(byte[] b, int limit) {
        return new InputStream() {
            int pos;
            @Override public int read() throws IOException {
                if (pos >= limit) throw new IOException("mat mang");
                return b[pos++] & 0xFF;
            }
            @Override public int read(byte[] buf, int off, int len) throws IOException {
                if (pos >= limit) throw new IOException("mat mang");
                int n = Math.min(len, limit - pos);
                System.arraycopy(b, pos, buf, off, n);
                pos += n;
                return n;
            }
        };
    }

    FileClient connect(String user, String pw) throws Exception {
        FileClient c = new FileClient("localhost", server.port(), ctx);
        c.login(user, pw);
        return c;
    }

    // ======================= Muc FileService (khong qua mang) =======================

    @Test void interruptedWriteKeepsPartAndReportsItsSize(@TempDir Path dir) throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = dir.resolve("a.bin");
        assertEquals(0, fs.partSize(f));
        assertThrows(IOException.class, () -> fs.write(f, dropAfter(DATA, 100_000), DATA.length, () -> shaOfData, ChunkListener.NONE));
        assertFalse(Files.exists(f));                               // chua xong thi chua co file that
        assertEquals(100_000, fs.partSize(f));                      // nhung .part duoc giu
        assertEquals(100_000, Files.size(dir.resolve("a.bin.part")));
    }

    @Test void resumeCompletesTheFileWithWholeFileSha(@TempDir Path dir) throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = dir.resolve("a.bin");
        assertThrows(IOException.class, () -> fs.write(f, dropAfter(DATA, 100_000), DATA.length, () -> shaOfData, ChunkListener.NONE));
        long have = fs.partSize(f);
        long total = fs.write(f, bytes(DATA, (int) have, DATA.length), have, DATA.length, () -> shaOfData, ChunkListener.NONE);
        assertEquals(DATA.length, total);
        assertArrayEquals(DATA, Files.readAllBytes(f));
        assertFalse(Files.exists(dir.resolve("a.bin.part")));       // xong thi het .part
    }

    @Test void resumeWithWrongOffsetIsRejectedAndKeepsPart(@TempDir Path dir) throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = dir.resolve("a.bin");
        assertThrows(IOException.class, () -> fs.write(f, dropAfter(DATA, 100_000), DATA.length, () -> shaOfData, ChunkListener.NONE));
        FsException e = assertThrows(FsException.class,
                () -> fs.write(f, bytes(DATA, 90_000, DATA.length), 90_000, DATA.length, () -> shaOfData, ChunkListener.NONE));
        assertEquals(ErrorCode.BAD_REQUEST, e.code());
        assertEquals(100_000, fs.partSize(f));                      // sai offset khong duoc lam mat .part dung
        FsException e2 = assertThrows(FsException.class,            // offset > 0 ma khong co .part
                () -> fs.write(dir.resolve("none.bin"), bytes(DATA, 5, 10), 5, 10, () -> "x", ChunkListener.NONE));
        assertEquals(ErrorCode.BAD_REQUEST, e2.code());
    }

    @Test void resumeOnCorruptedPartFailsShaAndDropsPart(@TempDir Path dir) throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = dir.resolve("a.bin");
        assertThrows(IOException.class, () -> fs.write(f, dropAfter(DATA, 100_000), DATA.length, () -> shaOfData, ChunkListener.NONE));
        byte[] part = Files.readAllBytes(dir.resolve("a.bin.part"));
        part[10] ^= 0x55;                                           // hong 1 byte trong phan da luu
        Files.write(dir.resolve("a.bin.part"), part);
        FsException e = assertThrows(FsException.class,
                () -> fs.write(f, bytes(DATA, 100_000, DATA.length), 100_000, DATA.length, () -> shaOfData, ChunkListener.NONE));
        assertEquals(ErrorCode.TOO_LARGE, e.code());                // dung ma 413 cua hop dong
        assertFalse(Files.exists(f));
        assertFalse(Files.exists(dir.resolve("a.bin.part")));
    }

    @Test void restartFromZeroOverwritesOldPart(@TempDir Path dir) throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = dir.resolve("a.bin");
        assertThrows(IOException.class, () -> fs.write(f, dropAfter(DATA, 100_000), DATA.length, () -> shaOfData, ChunkListener.NONE));
        fs.write(f, new ByteArrayInputStream(DATA), DATA.length, () -> shaOfData, ChunkListener.NONE);   // offset 0
        assertArrayEquals(DATA, Files.readAllBytes(f));
    }

    @Test void readFromOffsetSendsOnlyTheRestAndHashesThePrefix(@TempDir Path dir) throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = Files.write(dir.resolve("r.bin"), DATA);
        ByteArrayOutputStream rest = new ByteArrayOutputStream(), skipped = new ByteArrayOutputStream();
        long sent = fs.read(f, 250_000, rest, skipped, ChunkListener.NONE);
        assertEquals(DATA.length - 250_000, sent);
        assertArrayEquals(Arrays.copyOfRange(DATA, 250_000, DATA.length), rest.toByteArray());
        assertArrayEquals(Arrays.copyOfRange(DATA, 0, 250_000), skipped.toByteArray());
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(FsException.class,
                () -> fs.read(f, DATA.length + 1, rest, skipped, ChunkListener.NONE)).code());
    }

    // ======================= Muc tich hop (server that + FileClient that) =======================

    @Test void uploadAfterRealConnectionDropSendsOnlyTheRemainder() throws Exception {
        Path src = Files.write(tmp.resolve("big-src.bin"), DATA);
        FileClient c1 = connect("alice", "alice123");
        assertThrows(IOException.class, () -> c1.upload("/big.bin", src, (done, total) -> {
            if (done > total / 2) { try { c1.close(); } catch (IOException ignored) {} }   // "rut day mang" giua chung
        }));
        Path part = root.resolve("big.bin.part");
        long stable = -1;                                           // cho server xu ly xong ket noi chet
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            long now = Files.exists(part) ? Files.size(part) : -1;
            if (now > 0 && now == stable) break;
            stable = now;
        }
        long kept = Files.size(part);
        assertTrue(kept > 0 && kept < DATA.length, "phai giu phan da nhan, kept=" + kept);
        assertFalse(Files.exists(root.resolve("big.bin")));

        long[] first = {-1};
        try (FileClient c2 = connect("alice", "alice123")) {
            assertEquals(kept, c2.remotePartSize("/big.bin"));      // client hoi duoc "da co bao nhieu byte"
            long[] last = {0};
            c2.upload("/big.bin", src, (done, total) -> { if (first[0] < 0) first[0] = done; last[0] = done; });
            assertEquals(DATA.length, last[0]);
            assertTrue(c2.list("/").stream().anyMatch(i -> i.name().equals("big.bin") && i.size() == DATA.length));
        }
        assertEquals(kept, first[0]);                               // tien trinh bat dau tu byte da co, khong phai 0
        assertArrayEquals(DATA, Files.readAllBytes(root.resolve("big.bin")));
        assertFalse(Files.exists(part));
        Files.delete(root.resolve("big.bin"));
    }

    @Test void uploadWithStalePartOfOtherContentFallsBackToFullUpload() throws Exception {
        Path src = Files.write(tmp.resolve("stale-src.bin"), DATA);
        byte[] garbage = new byte[50_000];
        new Random(99).nextBytes(garbage);                          // .part cua mot file KHAC
        Files.write(root.resolve("stale.bin.part"), garbage);
        try (FileClient c = connect("alice", "alice123")) {
            c.upload("/stale.bin", src, ChunkListener.NONE);
        }
        assertArrayEquals(DATA, Files.readAllBytes(root.resolve("stale.bin")));
        assertFalse(Files.exists(root.resolve("stale.bin.part")));
        Files.delete(root.resolve("stale.bin"));
    }

    @Test void uploadWhenPartAlreadyHasAllBytes() throws Exception {
        Path src = Files.write(tmp.resolve("full-src.bin"), DATA);
        Files.write(root.resolve("full.bin.part"), DATA);           // dut dung luc gui xong, chua kip doi ten
        try (FileClient c = connect("alice", "alice123")) {
            c.upload("/full.bin", src, ChunkListener.NONE);
        }
        assertArrayEquals(DATA, Files.readAllBytes(root.resolve("full.bin")));
        Files.delete(root.resolve("full.bin"));
    }

    @Test void partSizeNeedsWritePermission() throws Exception {
        try (FileClient c = connect("bob", "bob123")) {             // bob chi LIST/READ
            assertEquals(ErrorCode.FORBIDDEN, assertThrows(FsException.class, () -> c.remotePartSize("/x.bin")).code());
        }
    }

    @Test void downloadResumesFromLocalPart() throws Exception {
        Files.write(root.resolve("dl.bin"), DATA);
        Path target = tmp.resolve("dl-out.bin"), part = tmp.resolve("dl-out.bin.part");
        Files.write(part, Arrays.copyOf(DATA, 123_456));            // lan truoc da tai duoc 123456 byte
        long[] first = {-1};
        try (FileClient c = connect("bob", "bob123")) {
            c.download("/dl.bin", target, (done, total) -> { if (first[0] < 0) first[0] = done; });
        }
        assertEquals(123_456, first[0]);                            // bat dau tu byte da co
        assertArrayEquals(DATA, Files.readAllBytes(target));
        assertFalse(Files.exists(part));
        Files.delete(root.resolve("dl.bin"));
    }

    @Test void downloadWithCorruptLocalPartRestartsAndStillSucceeds() throws Exception {
        Files.write(root.resolve("dl2.bin"), DATA);
        Path target = tmp.resolve("dl2-out.bin");
        byte[] bad = Arrays.copyOf(DATA, 70_000);
        bad[5] ^= 0x7F;                                             // phan da luu bi hong
        Files.write(tmp.resolve("dl2-out.bin.part"), bad);
        try (FileClient c = connect("bob", "bob123")) {
            c.download("/dl2.bin", target, ChunkListener.NONE);
        }
        assertArrayEquals(DATA, Files.readAllBytes(target));
        Files.delete(root.resolve("dl2.bin"));
    }

    @Test void downloadInterruptedKeepsPartAndNeverCreatesTarget() throws Exception {
        Files.write(root.resolve("dl3.bin"), DATA);
        Path target = tmp.resolve("dl3-out.bin"), part = tmp.resolve("dl3-out.bin.part");
        FileClient c1 = connect("bob", "bob123");
        assertThrows(IOException.class, () -> c1.download("/dl3.bin", target, (done, total) -> {
            if (done > total / 3) { try { c1.close(); } catch (IOException ignored) {} }
        }));
        assertFalse(Files.exists(target));                          // chua xong thi khong co file dich nua
        assertTrue(Files.exists(part) && Files.size(part) > 0);
        try (FileClient c2 = connect("bob", "bob123")) {
            c2.download("/dl3.bin", target, ChunkListener.NONE);
        }
        assertArrayEquals(DATA, Files.readAllBytes(target));
        Files.delete(root.resolve("dl3.bin"));
    }
}
