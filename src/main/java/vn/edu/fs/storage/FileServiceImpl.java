package vn.edu.fs.storage;

import vn.edu.fs.common.*;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/** Nhan lam list/mkdir/delete (C2). Long se them size/read/write (C3-C5) va khoa file (C4). */
public final class FileServiceImpl implements FileService {

    private static final int BUF = 32 * 1024;                         // 32 KB moi khoi
    private static final long DEFAULT_MAX_BYTES = 1L << 30;           // 1 GiB
    private final LockManager locks;
    private final long maxBytes;

    public FileServiceImpl(LockManager locks, long maxBytes) {
        this.locks = locks;
        this.maxBytes = maxBytes;
    }

    public FileServiceImpl(LockManager locks) {
        this(locks, DEFAULT_MAX_BYTES);
    }

    public FileServiceImpl() {                                        // giu cho test cua Nhan van chay
        this(new LockManager());
    }

    // ===================== Phan cua NHAN (C2) =====================
    @Override public List<FileInfo> list(Path dir) throws IOException {
        if (!Files.exists(dir)) throw new NoSuchFileException(dir.toString());
        if (!Files.isDirectory(dir)) throw new FsException(ErrorCode.BAD_REQUEST, "Khong phai thu muc");
        List<FileInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.endsWith(".part")) continue;                 // an file tam dang ghi
                boolean d = Files.isDirectory(p);
                out.add(new FileInfo(name, d, d ? 0 : Files.size(p), Files.getLastModifiedTime(p).toMillis()));
            }
        }
        out.sort(Comparator.comparing(FileInfo::name));
        return out;
    }

    @Override public void mkdir(Path dir) throws IOException {
        try { Files.createDirectory(dir); }
        catch (FileAlreadyExistsException e) { throw new FsException(ErrorCode.BAD_REQUEST, "Da ton tai"); }
    }

    @Override public void delete(Path p) throws IOException {
        try (LockManager.Held h = locks.write(p)) {                   // khong xoa file dang co nguoi doc/ghi (LOCKED 409)
            Files.delete(p);                                          // chi xoa file hoac thu muc rong
        } catch (DirectoryNotEmptyException e) {
            throw new FsException(ErrorCode.BAD_REQUEST, "Thu muc khong rong");
        }
    }

    // ===================== Phan cua LONG (C3) =====================
    @Override public long size(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new NoSuchFileException(file.toString());
        }
        if (!Files.isRegularFile(file)) {
            throw new FsException(ErrorCode.BAD_REQUEST, "Khong phai file");
        }
        return Files.size(file);
    }

    /** Doc theo khoi 32 KB duoi khoa doc; khong bao gio nap ca file vao RAM. offset byte dau chuyen vao skipped. */
    @Override public long read(Path file, long offset, OutputStream out, OutputStream skipped, ChunkListener l) throws IOException {
        long total = size(file);
        if (offset < 0 || offset > total) {
            throw new FsException(ErrorCode.BAD_REQUEST, "Offset ngoai pham vi file");
        }
        try (LockManager.Held h = locks.read(file);
             InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buf = new byte[BUF];
            long skip = offset;
            while (skip > 0) {                                        // phan da co phia client: chi bam, khong gui
                int n = in.read(buf, 0, (int) Math.min(buf.length, skip));
                if (n == -1) throw new FsException(ErrorCode.BAD_REQUEST, "File ngan hon offset (da bi sua?)");
                skipped.write(buf, 0, n);
                skip -= n;
            }
            long sent = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sent += n;
                l.onProgress(offset + sent, total);
            }
            out.flush();
            return sent;
        }
    }

    private static Path partOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".part");
    }

    @Override public long partSize(Path file) throws IOException {
        Path part = partOf(file);
        try (LockManager.Held h = locks.read(file)) {                // dang co nguoi ghi thi cho, khong doc so dang nhay
            return Files.isRegularFile(part) ? Files.size(part) : 0;
        }
    }

    @Override public String partSha256(Path file) throws IOException {
        Path part = partOf(file);
        try (LockManager.Held h = locks.read(file)) {
            if (!Files.isRegularFile(part)) return "";
            MessageDigest md = FrameOutputStream.newSha256();
            rehash(part, Files.size(part), md);
            return HexFormat.of().formatHex(md.digest());
        }
    }

    /**
     * Ghi vao ten.part duoi khoa ghi, tinh SHA-256 ngay khi nhan tung khoi, kiem tra kich thuoc + SHA
     * roi moi doi ten nguyen tu. Sai kich thuoc/SHA hoac vuot gioi han -> xoa .part, nem FsException(TOO_LARGE)
     * (dung hop dong FileService va ServerTest: ma 413).
     * Resume: offset &gt; 0 thi noi tiep vao .part cu (phai dung offset byte), SHA bam lai phan cu truoc.
     * Ket noi dut giua chung -> giu .part de lan sau tiep tuc.
     */
    @Override public long write(Path file, InputStream in, long offset, long declaredSize, Supplier<String> expectedSha256, ChunkListener l) throws IOException {
        if (Files.isDirectory(file)) {
            throw new FsException(ErrorCode.BAD_REQUEST, "Dich la thu muc");
        }
        if (declaredSize > maxBytes) {                                // tu choi som
            throw new FsException(ErrorCode.TOO_LARGE, "File qua lon");
        }
        if (offset < 0 || offset > declaredSize) {
            throw new FsException(ErrorCode.BAD_REQUEST, "Offset ngoai pham vi file");
        }
        MessageDigest md = FrameOutputStream.newSha256();
        Path part = partOf(file);
        try (LockManager.Held h = locks.write(file)) {
            // Kiem tra offset TRUOC khi dong den .part: sai offset thi .part cu van con nguyen.
            if (offset > 0) {
                long have = Files.isRegularFile(part) ? Files.size(part) : 0;
                if (have != offset) {
                    throw new FsException(ErrorCode.BAD_REQUEST,
                            "Khong tiep tuc duoc: .part co " + have + " byte, yeu cau offset " + offset);
                }
            }
            boolean discard = false;                                  // chi xoa .part khi that bai "dut khoat"
            try {
                long total = offset;
                if (offset > 0) {
                    rehash(part, offset, md);                         // SHA phai phu CA file, ke ca phan da co
                }
                try (OutputStream o = new BufferedOutputStream(offset > 0
                        ? Files.newOutputStream(part, StandardOpenOption.APPEND)
                        : Files.newOutputStream(part))) {             // offset 0: tao moi / cat ve 0
                    byte[] buf = new byte[BUF];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > maxBytes) {                       // tu choi muon: nhan qua nhieu
                            throw new FsException(ErrorCode.TOO_LARGE, "File qua lon");
                        }
                        md.update(buf, 0, n);
                        o.write(buf, 0, n);
                        l.onProgress(total, declaredSize);
                    }
                }
                String actual = HexFormat.of().formatHex(md.digest());
                String expected = expectedSha256.get();
                if (total != declaredSize || expected == null || !actual.equalsIgnoreCase(expected)) {
                    throw new FsException(ErrorCode.TOO_LARGE, "Sai kich thuoc hoac SHA-256");
                }
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return total;
            } catch (FsException e) {
                discard = true;                                       // sai SHA / vuot gioi han: .part vo dung
                throw e;
            } finally {
                // IOException khac (dut mang, EOF): giu .part de resume. Thanh cong thi .part da duoc doi ten.
                if (discard) Files.deleteIfExists(part);
            }
        }
    }

    /** Doc dung {@code count} byte dau cua file dua vao md. */
    private static void rehash(Path f, long count, MessageDigest md) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f))) {
            byte[] buf = new byte[BUF];
            long left = count;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n == -1) throw new EOFException(".part ngan hon du kien");
                md.update(buf, 0, n);
                left -= n;
            }
        }
    }
}
