package vn.edu.fs.acl;

import vn.edu.fs.common.AclService;
import vn.edu.fs.common.Permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Doc luat ACL tu file (TAB): path  principal  ALLOW|DENY  PERM1,PERM2
 * Thuat toan quyet dinh isAllowed duoc cai dat o B6 (#13).
 */
public final class AclServiceImpl implements AclService {
    private final List<AclRule> rules;
    private final UserDirectory dir;

    public AclServiceImpl(List<AclRule> rules, UserDirectory dir) {
        this.rules = List.copyOf(rules);
        this.dir = dir;
    }

    public static List<AclRule> load(Path file) throws IOException {
        List<AclRule> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            Set<Permission> perms = EnumSet.noneOf(Permission.class);
            for (String p : f[3].split(",")) perms.add(Permission.valueOf(p.trim()));
            out.add(new AclRule(f[0], f[1], f[2].equals("DENY"), perms));
        }
        return out;
    }

    // B6 (#13) se cai dat that. Tam thoi tu choi het (an toan: mac dinh tu choi).
    @Override public boolean isAllowed(String userId, String path, Permission p) {
        return false;
    }
}