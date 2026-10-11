package vn.edu.fs.common;

/** Ma loi trong giao thuc (xem hop dong giao thuc, muc 3). */
public enum ErrorCode {
    BAD_REQUEST(400), UNAUTHORIZED(401), FORBIDDEN(403), NOT_FOUND(404),
    LOCKED(409), TOO_LARGE(413), INTERNAL(500);

    public final int code;
    ErrorCode(int code) { this.code = code; }
}
