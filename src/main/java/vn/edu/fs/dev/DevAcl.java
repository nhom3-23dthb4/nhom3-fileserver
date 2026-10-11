package vn.edu.fs.dev;

import vn.edu.fs.common.AclService;
import vn.edu.fs.common.Permission;

/** TAM THOI: alice lam moi thu; bob chi LIST/READ va bi chan o /secret. B se thay. */
public final class DevAcl implements AclService {
    @Override public boolean isAllowed(String userId, String path, Permission p) {
        if (userId.equals("alice")) return true;
        if (path.equals("/secret") || path.startsWith("/secret/")) return false;
        return p == Permission.LIST || p == Permission.READ;
    }
}
