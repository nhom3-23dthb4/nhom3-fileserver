package vn.edu.fs.common;

/** Duong dan thoat khoi sandbox (Path Traversal) -> ma 400. */
public class PathEscapeException extends FsException {
    public PathEscapeException(String virtualPath) {
        super(ErrorCode.BAD_REQUEST, "Duong dan khong hop le");
    }
}
