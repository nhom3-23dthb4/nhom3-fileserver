package vn.edu.fs.acl;

import vn.edu.fs.common.Permission;

import java.util.Set;

/** Mot quy tac: path (tien to thu muc ao), principal ("user:alice" hoac "group:staff"), ALLOW/DENY, cac quyen. */
public record AclRule(String path, String principal, boolean deny, Set<Permission> perms) {}