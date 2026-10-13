package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.acl.AclServiceImpl;
import vn.edu.fs.auth.AuthServiceImpl;
import vn.edu.fs.common.Permission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AclServiceTest {
    @TempDir Path tmp;

    private AclServiceImpl make() throws Exception {
        Files.write(tmp.resolve("users.txt"), List.of(
            AuthServiceImpl.userLine("alice", "alice123".toCharArray(), "staff", "user"),
            AuthServiceImpl.userLine("bob", "bob123".toCharArray(), "staff", "user"),
            AuthServiceImpl.userLine("root", "root123".toCharArray(), "", "admin")));
        Files.write(tmp.resolve("acl.txt"), List.of(
            "# path TAB principal TAB ALLOW|DENY TAB quyen",
            "/\tgroup:staff\tALLOW\tLIST",
            "/projects\tgroup:staff\tALLOW\tLIST,READ,WRITE",
            "/projects/secret\tuser:bob\tDENY\tREAD,WRITE",
            "/projects/secret\tuser:alice\tALLOW\tREAD,WRITE,DELETE",
            "/public\tgroup:staff\tALLOW\tLIST,READ"));
        AuthServiceImpl auth = new AuthServiceImpl(tmp.resolve("users.txt"));
        return new AclServiceImpl(AclServiceImpl.load(tmp.resolve("acl.txt")), auth);
    }

    @Test void basicAllowAndDefaultDeny() throws Exception {
        AclServiceImpl acl = make();
        assertTrue(acl.isAllowed("alice", "/", Permission.LIST));
        assertFalse(acl.isAllowed("alice", "/", Permission.READ));                    // chi co LIST
        assertTrue(acl.isAllowed("alice", "/projects/a.txt", Permission.WRITE));
        assertFalse(acl.isAllowed("alice", "/projects/a", Permission.DELETE));       // khong co rule DELETE -> tu choi
        assertFalse(acl.isAllowed("alice", "/khac/x", Permission.READ));              // khong rule -> tu choi
    }

    @Test void prefixIsPerPathComponent() throws Exception {
        AclServiceImpl acl = make();
        assertFalse(acl.isAllowed("alice", "/projects-old/a.txt", Permission.READ)); // khong khop tien to
    }

    @Test void mostSpecificRuleAndDenyWins() throws Exception {
        AclServiceImpl acl = make();
        assertFalse(acl.isAllowed("bob", "/projects/secret/x", Permission.READ));   // DENY cu the hon
        assertTrue(acl.isAllowed("bob", "/projects/other", Permission.READ));
        assertTrue(acl.isAllowed("alice", "/projects/secret/x", Permission.READ));   // alice duoc ALLOW rieng
    }

    @Test void adminAllowedEverywhere() throws Exception {
        assertTrue(make().isAllowed("root", "/bat-ky", Permission.DELETE));
    }
}