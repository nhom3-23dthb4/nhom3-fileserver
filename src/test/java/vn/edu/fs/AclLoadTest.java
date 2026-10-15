package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.acl.AclRule;
import vn.edu.fs.acl.AclServiceImpl;
import vn.edu.fs.common.Permission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AclLoadTest {
    @TempDir Path tmp;

    @Test void loadParsesRulesAndSkipsCommentsAndBlankLines() throws Exception {
        Path f = tmp.resolve("acl.txt");
        Files.write(f, List.of(
            "# path TAB principal TAB ALLOW|DENY TAB quyen",
            "",
            "/\tgroup:staff\tALLOW\tLIST",
            "/projects\tgroup:staff\tALLOW\tLIST,READ,WRITE",
            "/projects/secret\tuser:bob\tDENY\tREAD,WRITE"));
        List<AclRule> rules = AclServiceImpl.load(f);
        assertEquals(3, rules.size());                                   // bo dong chu thich va dong trong

        AclRule r0 = rules.get(0);
        assertEquals("/", r0.path());
        assertEquals("group:staff", r0.principal());
        assertFalse(r0.deny());
        assertEquals(Set.of(Permission.LIST), r0.perms());

        AclRule r1 = rules.get(1);
        assertEquals(Set.of(Permission.LIST, Permission.READ, Permission.WRITE), r1.perms());

        AclRule r2 = rules.get(2);
        assertTrue(r2.deny());                                           // DENY -> deny = true
        assertEquals("user:bob", r2.principal());
        assertEquals(Set.of(Permission.READ, Permission.WRITE), r2.perms());
    }

    @Test void loadRejectsUnknownPermission() throws Exception {
        Path f = tmp.resolve("bad.txt");
        Files.write(f, List.of("/x\tuser:a\tALLOW\tFLY"));
        assertThrows(IllegalArgumentException.class, () -> AclServiceImpl.load(f));
    }
}