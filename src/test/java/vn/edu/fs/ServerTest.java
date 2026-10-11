package vn.edu.fs;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.common.*;
import vn.edu.fs.dev.*;
import vn.edu.fs.server.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Test tich hop cua A: server that + client TLS that, voi B/C la ban dev tam. */
class ServerTest {
    static final char[] PW = "changeit".toCharArray();
    @TempDir static Path tmp;
    static FileServer server;
    static Path ks, root;

    @BeforeAll static void start() throws Exception {
        ks = tmp.resolve("server.p12");
        Process p = new ProcessBuilder("keytool", "-genkeypair", "-alias", "fs", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "changeit",
                "-keypass", "changeit", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("secret"));
        Files.writeString(root.resolve("hello.txt"), "xin chao");
        server = new FileServer(new FileServer.Config(0, ks, PW, 10_000_000L, 5_000, 8),
                new DevAuth(), new DevAcl(), new DevSandbox(root), new DevFileService(),
                new AuditLogger(tmp.resolve("audit.log")));
        server.start();
    }
    @AfterAll static void stop() throws Exception { server.close(); }

    /** Client toi gian dung TLS 1.3, tin cay dung cert cua server. */
    static class Cli implements AutoCloseable {
        final SSLSocket s; final DataInputStream in; final DataOutputStream out;
        Cli(String... protocols) throws Exception {
            SSLContext ctx = protocols.length == 0 ? TlsConfig.clientContext(ks, PW) : tls12ctx();
            s = (SSLSocket) ctx.getSocketFactory().createSocket("localhost", server.port());
            s.setSoTimeout(5000);
            s.setEnabledProtocols(protocols.length == 0 ? TlsConfig.PROTOCOLS : protocols);
            s.startHandshake();
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        }
        static SSLContext tls12ctx() throws Exception {
            var tmf = javax.net.ssl.TrustManagerFactory.getInstance("PKIX");
            var k = java.security.KeyStore.getInstance("PKCS12");
            try (var i = Files.newInputStream(ks)) { k.load(i, PW); }
            tmf.init(k);
            var c = SSLContext.getInstance("TLS");
            c.init(null, tmf.getTrustManagers(), null);
            return c;
        }
        String cmd(String text) throws IOException { FrameIO.writeControl(out, text); return in(); }
        String in() throws IOException { Frame f = FrameIO.read(in); return f == null ? null : f.text(); }
        void login(String u) throws IOException { assertTrue(cmd("LOGIN\t" + u + "\t" + u + "123").startsWith("OK")); }
        @Override public void close() throws IOException { s.close(); }
    }

    @Test void loginWrongPasswordGives401() throws Exception {
        try (Cli c = new Cli()) { assertTrue(c.cmd("LOGIN\talice\tsai").startsWith("ERR\t401")); }
    }

    @Test void commandBeforeLoginGives401() throws Exception {
        try (Cli c = new Cli()) { assertTrue(c.cmd("LIST\t/").startsWith("ERR\t401")); }
    }

    @Test void listAndReadSmallFile() throws Exception {
        try (Cli c = new Cli()) {
            c.login("bob");
            String head = c.cmd("LIST\t/");
            assertTrue(head.startsWith("OK\t"));
            int rows = Integer.parseInt(head.substring(3));
            boolean sawHello = false;
            for (int i = 0; i < rows; i++) sawHello |= c.in().startsWith("hello.txt\tF\t8\t");
            assertTrue(sawHello);
            assertEquals("OK\t8", c.cmd("READ\t/hello.txt"));
            Frame d = FrameIO.read(c.in);
            assertEquals(Frame.DATA, d.type());
            assertEquals("xin chao", d.text());
            assertEquals(Frame.END, FrameIO.read(c.in).type());
        }
    }

    @Test void denyIsReturnedAndLogged() throws Exception {
        try (Cli c = new Cli()) {
            c.login("bob");
            assertTrue(c.cmd("READ\t/secret/x").startsWith("ERR\t403"));
            assertTrue(c.cmd("WRITE\t/new.txt\t3").startsWith("ERR\t403"));   // khong duoc gui du lieu sau DENY
        }
    }

    @Test void aclSeesCanonicalPathNotRawPath() throws Exception {
        try (Cli c = new Cli()) {                                // /a/../secret/x phai bi coi la /secret/x
            c.login("bob");
            assertTrue(c.cmd("READ\t/a/../secret/x").startsWith("ERR\t403"));
        }
    }

    @Test void pathTraversalGives400() throws Exception {
        try (Cli c = new Cli()) {
            c.login("alice");
            assertTrue(c.cmd("READ\t/../etc/passwd").startsWith("ERR\t400"));
            assertTrue(c.cmd("LIST\t/..").startsWith("ERR\t400"));
        }
    }

    @Test void missingFileGives404() throws Exception {
        try (Cli c = new Cli()) { c.login("alice"); assertTrue(c.cmd("READ\t/khong-co").startsWith("ERR\t404")); }
    }

    @Test void uploadThenDownloadLargeFileKeepsSha() throws Exception {
        byte[] data = new byte[3_000_000];                       // ~92 khung 32 KB
        new java.util.Random(1).nextBytes(data);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        try (Cli c = new Cli()) {
            c.login("alice");
            assertEquals("OK", c.cmd("WRITE\t/big.bin\t" + data.length));
            for (int off = 0; off < data.length; off += 32768)
                FrameIO.write(c.out, Frame.DATA, data, off, Math.min(32768, data.length - off));
            FrameIO.write(c.out, Frame.END, sha.getBytes(StandardCharsets.UTF_8), 0, sha.length());
            c.out.flush();
            assertEquals("OK", c.in());
            assertEquals("OK\t" + data.length, c.cmd("READ\t/big.bin"));
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            Frame f; int frames = 0;
            while ((f = FrameIO.read(c.in)).type() == Frame.DATA) { got.write(f.payload()); frames++; }
            assertEquals(Frame.END, f.type());
            assertEquals(sha, f.text());
            assertArrayEquals(data, got.toByteArray());
            assertTrue(frames > 90);
        }
        assertFalse(Files.exists(root.resolve("big.bin.part")));
    }

    @Test void wrongShaGives413AndConnectionStaysUsable() throws Exception {
        try (Cli c = new Cli()) {
            c.login("alice");
            assertEquals("OK", c.cmd("WRITE\t/bad.txt\t3"));
            FrameIO.write(c.out, Frame.DATA, "abc".getBytes(), 0, 3);
            FrameIO.write(c.out, Frame.END, "00".getBytes(), 0, 2);
            c.out.flush();
            assertTrue(c.in().startsWith("ERR\t413"));
            assertFalse(Files.exists(root.resolve("bad.txt")));
            assertTrue(c.cmd("LIST\t/").startsWith("OK"));       // khung con dong bo
        }
    }

    @Test void sendingMoreThanDeclaredIsRejected() throws Exception {
        try (Cli c = new Cli()) {
            c.login("alice");
            assertEquals("OK", c.cmd("WRITE\t/liar.txt\t3"));
            FrameIO.write(c.out, Frame.DATA, "abcdef".getBytes(), 0, 6);
            FrameIO.write(c.out, Frame.END, "00".getBytes(), 0, 2);
            c.out.flush();
            assertTrue(c.in().startsWith("ERR\t413"));
            assertFalse(Files.exists(root.resolve("liar.txt")));
        }
    }

    @Test void oversizedDeclaredFileRejectedBeforeUpload() throws Exception {
        try (Cli c = new Cli()) { c.login("alice"); assertTrue(c.cmd("WRITE\t/x\t99999999999").startsWith("ERR\t413")); }
    }

    @Test void hugePayloadLengthClosesConnection() throws Exception {
        try (Cli c = new Cli()) {
            c.out.writeByte(Frame.CONTROL); c.out.writeInt(70_000); c.out.flush();   // > 65536
            String r = c.in();
            assertTrue(r == null || r.startsWith("ERR\t400"));
            assertNull(c.in.read() == -1 ? null : "con-du-lieu");
        }
    }

    @Test void tls12IsRejected() throws Exception {
        assertThrows(IOException.class, () -> new Cli("TLSv1.2"));
    }

    @Test void fiveWrongLoginsCloseConnection() throws Exception {
        try (Cli c = new Cli()) {
            for (int i = 0; i < 5; i++) assertTrue(c.cmd("LOGIN\talice\tx").startsWith("ERR\t401"));
            assertEquals(-1, c.in.read());
        }
    }

    @Test void manyClientsAtOnce() throws Exception {
        Thread[] ts = new Thread[20];
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                try (Cli c = new Cli()) { c.login("bob"); if (!c.cmd("READ\t/hello.txt").equals("OK\t8")) failures.incrementAndGet(); }
                catch (Exception e) { failures.incrementAndGet(); }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join(15_000);
        assertEquals(0, failures.get());
    }

    @Test void auditLogRecordsDeny() throws Exception {
        try (Cli c = new Cli()) { c.login("bob"); c.cmd("READ\t/secret/y"); }
        Thread.sleep(300);
        String log = Files.readString(tmp.resolve("audit.log"));
        assertTrue(log.contains("bob") && log.contains("/secret/y") && log.contains("DENY"));
        assertFalse(log.contains("bob123"));                     // khong bao gio ghi mat khau
    }
}
