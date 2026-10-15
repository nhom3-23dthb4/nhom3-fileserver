package vn.edu.fs.acl;

import vn.edu.fs.common.AclService;
import vn.edu.fs.common.Permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Thuat toan: (1) admin -> cho phep. (2) gom rule khop user/nhom + khop duong dan theo TUNG THANH PHAN
 * + co quyen can hoi. (3) lay nhom rule co path DAI NHAT (cu the nhat); trong nhom do DENY thang ALLOW.
 * (4) khong co rule -> tu choi.
 * File acl (TAB): path  principal  ALLOW|DENY  PERM1,PERM2
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

    @Override public boolean isAllowed(String userId, String path, Permission p) {
        if (dir.isAdmin(userId)) return true;
        Set<String> who = new HashSet<>();
        who.add("user:" + userId);
        for (String g : dir.groupsOf(userId)) who.add("group:" + g);

        int bestDepth = -1;
        boolean allow = false, deny = false;
        for (AclRule r : rules) {
            if (!who.contains(r.principal()) || !r.perms().contains(p) || !isPrefix(r.path(), path)) continue;
            int depth = depth(r.path());
            if (depth > bestDepth) { bestDepth = depth; allow = false; deny = false; }
            if (depth == bestDepth) { if (r.deny()) deny = true; else allow = true; }
        }
        return bestDepth >= 0 && allow && !deny;
    }

    /** "/projects" la tien to cua "/projects" va "/projects/a" nhung KHONG phai "/projects-old". */
    static boolean isPrefix(String prefix, String path) {
        if (prefix.equals("/")) return true;
        if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    static int depth(String prefix) {
        if (prefix.equals("/")) return 0;
        int n = 0;
        for (char c : prefix.toCharArray()) if (c == '/') n++;
        return n;
    }
}