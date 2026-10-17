package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.common.ChunkListener;
import vn.edu.fs.storage.FileServiceImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceChunkTest {

    @TempDir Path dir;

    private static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void writeThenReadBackGivesSameBytes() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        byte[] data = new byte[200_000];                          // lon hon 6 khoi 32 KB
        new Random(1).nextBytes(data);
        Path f = dir.resolve("a.bin");

        String h = sha(data);
        fs.write(f, new ByteArrayInputStream(data), data.length, () -> h, ChunkListener.NONE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fs.read(f, out, ChunkListener.NONE);
        assertArrayEquals(data, out.toByteArray());
        assertEquals(data.length, fs.size(f));
    }

    @Test
    void noPartFileLeftAfterWrite() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = dir.resolve("b.bin");
        String h = sha(new byte[10]);
        fs.write(f, new ByteArrayInputStream(new byte[10]), 10, () -> h, ChunkListener.NONE);
        assertTrue(Files.exists(f));
        assertFalse(Files.exists(dir.resolve("b.bin.part")));
    }

    @Test
    void sizeOfMissingFileThrowsNoSuchFile() {
        FileServiceImpl fs = new FileServiceImpl();
        assertThrows(NoSuchFileException.class, () -> fs.size(dir.resolve("nope")));
    }
}
