package vn.edu.fs.acl;

import java.util.Set;

/** AclService hoi "user nay thuoc nhom nao, co phai admin khong". AuthServiceImpl cai dat. */
public interface UserDirectory {
    Set<String> groupsOf(String userId);
    boolean isAdmin(String userId);
}