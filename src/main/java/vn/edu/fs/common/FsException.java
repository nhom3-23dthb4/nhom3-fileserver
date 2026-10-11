package vn.edu.fs.common;

import java.io.IOException;

/** Loi nghiep vu co ma loi giao thuc. B va C nem ra, A chuyen thanh "ERR code message". */
public class FsException extends IOException {
    private final ErrorCode code;
    public FsException(ErrorCode code, String message) { super(message); this.code = code; }
    public ErrorCode code() { return code; }
}
