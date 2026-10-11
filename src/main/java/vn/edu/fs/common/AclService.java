package vn.edu.fs.common;

/** B trien khai. virtualPath luon la duong dan ao DA CHUAN HOA, bat dau bang "/". */
public interface AclService {
    boolean isAllowed(String userId, String virtualPath, Permission p);
}
