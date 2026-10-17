package vn.edu.fs.storage;

import vn.edu.fs.common.*;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        try {
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

    /** Doc theo khoi 32 KB duoi khoa doc; khong bao gio nap ca file vao RAM. */
    @Override public long read(Path file, OutputStream out, ChunkListener l) throws IOException {
        long total = size(file);
        try (LockManager.Held h = locks.read(file);
             InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buf = new byte[BUF];
            long sent = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sent += n;
                l.onProgress(sent, total);
            }
            out.flush();
            return sent;
        }
    }

    /**
     * Ghi vao ten.part duoi khoa ghi, tinh SHA-256 ngay khi nhan tung khoi, kiem tra kich thuoc + SHA
     * roi moi doi ten nguyen tu. Sai kich thuoc/SHA hoac vuot gioi han -> xoa .part, nem FsException(TOO_LARGE)
     * (dung hop dong FileService va ServerTest: ma 413).
     */
    @Override public long write(Path file, InputStream in, long declaredSize, Supplier<String> expectedSha256, ChunkListener l) throws IOException {
        if (Files.isDirectory(file)) {
            throw new FsException(ErrorCode.BAD_REQUEST, "Dich la thu muc");
        }
        if (declaredSize > maxBytes) {                                // tu choi som
            throw new FsException(ErrorCode.TOO_LARGE, "File qua lon");
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);                       // JDK nao cung co SHA-256
        }
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try (LockManager.Held h = locks.write(file)) {
            long total = 0;
            try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(part))) {
                byte[] buf = new byte[BUF];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) {                           // tu choi muon: nhan qua nhieu
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
        } finally {
            Files.deleteIfExists(part);                               // don file tam du thanh cong hay loi
        }
    }
}
