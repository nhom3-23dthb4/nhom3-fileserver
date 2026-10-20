package vn.edu.fs.server;

import vn.edu.fs.acl.AclServiceImpl;
import vn.edu.fs.auth.AuthServiceImpl;
import vn.edu.fs.storage.FileServiceImpl;
import vn.edu.fs.storage.LockManager;
import vn.edu.fs.storage.PathSandboxImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Diem vao cua may chu. Cau hinh qua bien moi truong:
 * FS_PORT (8443), FS_ROOT (storage-root), FS_KEYSTORE (certs/server.p12), FS_KEYSTORE_PASSWORD (bat buoc),
 * FS_USERS (config/users.tsv), FS_ACL (config/acl.tsv).
 *
 * Lan chay dau tien neu chua co file nguoi dung/ACL thi tu tao du lieu demo:
 * alice/alice123 (admin, toan quyen), bob/bob123 (chi LIST/READ, bi chan /secret).
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
        Path usersFile = Path.of(System.getenv().getOrDefault("FS_USERS", "config/users.tsv"));
        Path aclFile = Path.of(System.getenv().getOrDefault("FS_ACL", "config/acl.tsv"));

        seedDemoData(root, usersFile, aclFile);

        var auth = new AuthServiceImpl(usersFile);
        var acl = new AclServiceImpl(AclServiceImpl.load(aclFile), auth);
        var sandbox = new PathSandboxImpl(root);
        var files = new FileServiceImpl(new LockManager());

        var cfg = new FileServer.Config(port, ks, pw.toCharArray(), 1L << 30, 60_000, 32);
        var server = new FileServer(cfg, auth, acl, sandbox, files, new AuditLogger(Path.of("logs", "audit.log")));
        server.start();
        System.out.println("FileServer TLS1.3 dang nghe cong " + server.port() + ", goc sandbox: " + root);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } catch (Exception ignored) {}
        }));
    }

    /** Tao thu muc goc, nguoi dung va luat ACL demo neu chua ton tai (khong ghi de du lieu co san). */
    private static void seedDemoData(Path root, Path usersFile, Path aclFile) throws IOException {
        Files.createDirectories(root.resolve("public"));
        Files.createDirectories(root.resolve("secret"));
        if (!Files.exists(usersFile)) {
            mkParent(usersFile);
            Files.write(usersFile, List.of(
                    AuthServiceImpl.userLine("alice", "alice123".toCharArray(), "", "admin"),
                    AuthServiceImpl.userLine("bob", "bob123".toCharArray(), "readers", "user")));
        }
        if (!Files.exists(aclFile)) {
            mkParent(aclFile);
            Files.write(aclFile, List.of(
                    "/\tgroup:readers\tALLOW\tLIST,READ",
                    "/secret\tgroup:readers\tDENY\tLIST,READ,WRITE,DELETE"));
        }
    }

    private static void mkParent(Path f) throws IOException {
        Path p = f.toAbsolutePath().getParent();
        if (p != null) Files.createDirectories(p);
    }
}
