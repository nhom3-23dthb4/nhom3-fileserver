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
    default long read(Path file, OutputStream out, ChunkListener l) throws IOException {
        return read(file, 0, out, OutputStream.nullOutputStream(), l);
    }

    /**
     * Doc file tu byte thu {@code offset} (tiep tuc tai xuong). {@code offset} byte dau KHONG gui di ma chuyen
     * vao {@code skipped} (de ben goi tinh SHA-256 cua CA file). offset &gt; size -> FsException(BAD_REQUEST).
     * @return so byte da ghi vao {@code out} (khong tinh phan bo qua)
     */
    long read(Path file, long offset, OutputStream out, OutputStream skipped, ChunkListener l) throws IOException;

    /**
     * Ghi file tu stream theo khoi vao ten.part, doc xong moi doi ten. Sau khi in tra -1,
     * so SHA-256 tinh duoc voi expectedSha256.get(); sai -> xoa .part va nem FsException(TOO_LARGE).
     */
    default long write(Path file, InputStream in, long declaredSize, Supplier<String> expectedSha256, ChunkListener l) throws IOException {
        return write(file, in, 0, declaredSize, expectedSha256, l);
    }

    /**
     * Ghi tiep (resume): {@code offset} = so byte da co trong ten.part (phai dung bang kich thuoc .part hien tai,
     * neu khong -> FsException(BAD_REQUEST), .part giu nguyen). {@code in} chi chua phan CON LAI (declaredSize - offset).
     * SHA-256 tinh tren CA file (phan .part cu + phan moi). offset = 0 thi ghi moi tu dau.
     * Ket noi dut giua chung (IOException) -> GIU .part de tiep tuc lan sau; sai SHA/kich thuoc -> xoa .part.
     */
    long write(Path file, InputStream in, long offset, long declaredSize, Supplier<String> expectedSha256, ChunkListener l) throws IOException;

    /** So byte dang co trong ten.part (0 neu khong co). Dung de client biet can tiep tuc tu dau. */
    long partSize(Path file) throws IOException;

    /** SHA-256 (hex) cua {@code ten.part} hien co; chuoi rong neu khong co .part. Client dung de biet .part co khop file nguon khong. */
    default String partSha256(Path file) throws IOException { return ""; }

    void mkdir(Path dir) throws IOException;
    void delete(Path p) throws IOException;
}
