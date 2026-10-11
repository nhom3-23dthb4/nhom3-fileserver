package vn.edu.fs.server;

import vn.edu.fs.dev.*;

import java.nio.file.Path;

/**
 * Diem vao. Cau hinh qua bien moi truong:
 * FS_PORT (8443), FS_ROOT (storage-root), FS_KEYSTORE (certs/server.p12), FS_KEYSTORE_PASSWORD (bat buoc).
 *
 * TODO khi B, C merge: thay 4 dong "new Dev..." trong wire() bang UserStore/AclService/PathSandbox/FileService that.
 */
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        String pw = System.getenv("FS_KEYSTORE_PASSWORD");
        if (pw == null || pw.isEmpty()) {
            System.err.println("Thieu bien moi truong FS_KEYSTORE_PASSWORD");
            System.exit(2);
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("FS_PORT", "8443"));
        Path root = Path.of(System.getenv().getOrDefault("FS_ROOT", "storage-root")).toAbsolutePath().normalize();
        Path ks = Path.of(System.getenv().getOrDefault("FS_KEYSTORE", "certs/server.p12"));

        var cfg = new FileServer.Config(port, ks, pw.toCharArray(), 1L << 30, 60_000, 32);
        var sandbox = new DevSandbox(root);
        var server = new FileServer(cfg, new DevAuth(), new DevAcl(), sandbox, new DevFileService(),
                new AuditLogger(Path.of("logs", "audit.log")));
        server.start();
        System.out.println("FileServer TLS1.3 dang nghe cong " + server.port() + ", goc sandbox: " + root);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } catch (Exception ignored) {}
        }));
    }
}
