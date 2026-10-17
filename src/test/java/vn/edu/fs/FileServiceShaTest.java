package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.common.ChunkListener;
import vn.edu.fs.common.ErrorCode;
import vn.edu.fs.common.FsException;
import vn.edu.fs.storage.FileServiceImpl;
import vn.edu.fs.storage.LockManager;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceShaTest {

    @TempDir Path dir;

    private static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private byte[] random(int n) {
        byte[] d = new byte[n];
        new Random(7).nextBytes(d);
        return d;
    }

    @Test
    void correctShaIsAccepted() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        byte[] data = random(100_000);
        String h = sha(data);
        fs.write(dir.resolve("ok.bin"), new ByteArrayInputStream(data), data.length, () -> h, ChunkListener.NONE);
        assertEquals(data.length, Files.size(dir.resolve("ok.bin")));
    }

    @Test
    void wrongShaIsRejectedAndLeavesNothing() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        byte[] data = random(100_000);
        FsException e = assertThrows(FsException.class, () ->
                fs.write(dir.resolve("bad.bin"), new ByteArrayInputStream(data), data.length, () -> "00", ChunkListener.NONE));
        assertEquals(ErrorCode.TOO_LARGE, e.code());              // hop dong FileService / ServerTest: ma 413
        assertFalse(Files.exists(dir.resolve("bad.bin")));
        assertFalse(Files.exists(dir.resolve("bad.bin.part")));
    }

    @Test
    void wrongShaDoesNotOverwriteExistingFile() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = dir.resolve("keep.bin");
        Files.write(f, new byte[]{1, 2, 3});
        byte[] data = random(1000);
        assertThrows(FsException.class, () ->
                fs.write(f, new ByteArrayInputStream(data), data.length, () -> "00", ChunkListener.NONE));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(f));   // file cu con nguyen
    }

    @Test
    void declaredSizeMismatchIsRejected() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        byte[] data = random(500);
        String h = sha(data);
        assertThrows(FsException.class, () ->
                fs.write(dir.resolve("m.bin"), new ByteArrayInputStream(data), 999, () -> h, ChunkListener.NONE));
        assertFalse(Files.exists(dir.resolve("m.bin")));
    }

    @Test
    void declaredTooLargeIsRejectedEarly() throws Exception {
        FileServiceImpl fs = new FileServiceImpl(new LockManager(), 1000);   // gioi han 1000 byte
        FsException e = assertThrows(FsException.class, () ->
                fs.write(dir.resolve("big.bin"), new ByteArrayInputStream(new byte[10]), 5000, () -> "00", ChunkListener.NONE));
        assertEquals(ErrorCode.TOO_LARGE, e.code());
    }

    @Test
    void sendingMoreThanLimitIsRejectedEvenIfDeclaredSmall() throws Exception {
        FileServiceImpl fs = new FileServiceImpl(new LockManager(), 1000);
        byte[] data = random(5000);
        FsException e = assertThrows(FsException.class, () ->
                fs.write(dir.resolve("liar.bin"), new ByteArrayInputStream(data), 10, () -> "00", ChunkListener.NONE));
        assertEquals(ErrorCode.TOO_LARGE, e.code());
        assertFalse(Files.exists(dir.resolve("liar.bin.part")));
    }
}
