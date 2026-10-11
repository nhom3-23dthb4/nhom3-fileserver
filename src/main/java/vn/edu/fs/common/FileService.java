package vn.edu.fs.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * C trien khai (tu lay khoa Read/Write ben trong; khong lay duoc sau 5 giay -> FsException(LOCKED)).
 * Khong tu kiem tra ACL (viec cua dispatcher). Loi nghiep vu nem FsException; thieu file nem NoSuchFileException.
 */
public interface FileService {
    List<FileInfo> list(Path dir) throws IOException;
    long size(Path file) throws IOException;
    /** Doc file theo khoi, ghi vao out; tra so byte da gui. */
    long read(Path file, OutputStream out, ChunkListener l) throws IOException;
    /**
     * Ghi file tu stream theo khoi vao ten.part, doc xong moi doi ten. Sau khi in tra -1,
     * so SHA-256 tinh duoc voi expectedSha256.get(); sai -> xoa .part va nem FsException(TOO_LARGE).
     */
    long write(Path file, InputStream in, long declaredSize, Supplier<String> expectedSha256, ChunkListener l) throws IOException;
    void mkdir(Path dir) throws IOException;
    void delete(Path p) throws IOException;
}
