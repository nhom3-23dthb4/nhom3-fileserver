package vn.edu.fs.common;

import java.nio.file.Path;

/** C trien khai. */
public interface PathSandbox {
    /** Duong dan ao -> Path that trong sandbox; nem PathEscapeException neu thoat sandbox. */
    Path resolve(String virtualPath) throws PathEscapeException;
    /** Path that trong sandbox -> duong dan ao chuan hoa, dung dau '/', bat dau '/'. */
    String toVirtual(Path real);
}
