package vn.edu.fs;

import vn.edu.fs.acl.AclServiceImpl;
import vn.edu.fs.auth.AuthServiceImpl;
import vn.edu.fs.common.AclService;
import vn.edu.fs.common.AuthService;
import vn.edu.fs.common.FileService;
import vn.edu.fs.common.PathSandbox;
import vn.edu.fs.storage.FileServiceImpl;
import vn.edu.fs.storage.LockManager;
import vn.edu.fs.storage.PathSandboxImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bo dich vu THAT dung chung cho cac test (thay cho cac lop dev cu).
 * alice/alice123 = admin (toan quyen); bob/bob123 = chi LIST/READ, bi chan /secret.
 */
final class TestServices {
    final AuthService auth;
    final AclService acl;
    final PathSandbox sandbox;
    final FileService files;

    private TestServices(AuthService auth, AclService acl, PathSandbox sandbox, FileService files) {
        this.auth = auth; this.acl = acl; this.sandbox = sandbox; this.files = files;
    }

    static TestServices create(Path workDir, Path root) throws IOException {
        Path users = workDir.resolve("users.tsv");
        Files.write(users, List.of(
                AuthServiceImpl.userLine("alice", "alice123".toCharArray(), "", "admin"),
                AuthServiceImpl.userLine("bob", "bob123".toCharArray(), "readers", "user")));
        Path aclFile = workDir.resolve("acl.tsv");
        Files.write(aclFile, List.of(
                "/\tgroup:readers\tALLOW\tLIST,READ",
                "/secret\tgroup:readers\tDENY\tLIST,READ,WRITE,DELETE"));
        AuthServiceImpl auth = new AuthServiceImpl(users);
        AclServiceImpl acl = new AclServiceImpl(AclServiceImpl.load(aclFile), auth);
        return new TestServices(auth, acl, new PathSandboxImpl(root), new FileServiceImpl(new LockManager()));
    }
}
