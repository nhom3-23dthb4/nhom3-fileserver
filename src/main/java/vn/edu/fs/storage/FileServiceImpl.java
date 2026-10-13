package vn.edu.fs.storage;

import vn.edu.fs.common.*;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Nhan lam list/mkdir/delete (C2). Long se them size/read/write (C3-C5) va khoa file (C4). */
public final class FileServiceImpl implements FileService {

    // ===================== Phan cua NHAN (C2) =====================
    @Override public List<FileInfo> list(Path dir) throws IOException {
        if (!Files.exists(dir)) throw new NoSuchFileException(dir.toString());
        if (!Files.isDirectory(dir)) throw new FsException(ErrorCode.BAD_REQUEST, "Khong phai thu muc");
        List<FileInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.endsWith(".part")) continue;                // an file tam dang ghi
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
            Files.delete(p);                                        // chi xoa file hoac thu muc rong
        } catch (DirectoryNotEmptyException e) {
            throw new FsException(ErrorCode.BAD_REQUEST, "Thu muc khong rong");
        }
    }

    // ===================== Phan cua LONG: de trong, Long se viet =====================
    @Override public long size(Path file) throws IOException { throw new UnsupportedOperationException("Long lam (C3)"); }
    @Override public long read(Path file, OutputStream out, ChunkListener l) throws IOException { throw new UnsupportedOperationException("Long lam (C3)"); }
    @Override public long write(Path file, InputStream in, long declaredSize, Supplier<String> expectedSha256, ChunkListener l) throws IOException { throw new UnsupportedOperationException("Long lam (C3)"); }
}