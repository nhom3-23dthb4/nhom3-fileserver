package vn.edu.fs.dev;

import vn.edu.fs.common.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** TAM THOI: chua co khoa Read/Write. C se thay bang ban co LockManager. */
public final class DevFileService implements FileService {
    @Override public List<FileInfo> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> {
                try {
                    return new FileInfo(p.getFileName().toString(), Files.isDirectory(p),
                            Files.isDirectory(p) ? 0 : Files.size(p), Files.getLastModifiedTime(p).toMillis());
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }).toList();
        } catch (UncheckedIOException e) { throw e.getCause(); }
    }

    @Override public long size(Path file) throws IOException {
        if (!Files.exists(file)) throw new NoSuchFileException(file.toString());
        if (!Files.isRegularFile(file)) throw new FsException(ErrorCode.BAD_REQUEST, "Khong phai file");
        return Files.size(file);
    }

    @Override public long read(Path file, OutputStream out, ChunkListener l) throws IOException {
        byte[] buf = new byte[32 * 1024];
        long sent = 0; int n;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); sent += n; }
        }
        return sent;
    }

    @Override public long write(Path file, InputStream in, long declaredSize, Supplier<String> sha, ChunkListener l) throws IOException {
        Path part = file.resolveSibling(file.getFileName() + ".part");
        byte[] buf = new byte[32 * 1024];
        long total = 0; int n;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(part))) {
                while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); md.update(buf, 0, n); total += n; }
            }
            if (total != declaredSize || !HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha.get()))
                throw new FsException(ErrorCode.TOO_LARGE, "Sai kich thuoc hoac SHA-256");
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return total;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    @Override public void mkdir(Path dir) throws IOException { Files.createDirectory(dir); }
    @Override public void delete(Path p) throws IOException { Files.delete(p); }
}
