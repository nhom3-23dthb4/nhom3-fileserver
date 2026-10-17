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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceChunkTest {

    @TempDir Path dir;

    @Test
    void writeThenReadBackGivesSameBytes() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        byte[] data = new byte[200_000];                          // lon hon 6 khoi 32 KB
        new Random(1).nextBytes(data);
        Path f = dir.resolve("a.bin");

        fs.write(f, new ByteArrayInputStream(data), data.length, () -> null, ChunkListener.NONE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fs.read(f, out, ChunkListener.NONE);
        assertArrayEquals(data, out.toByteArray());
        assertEquals(data.length, fs.size(f));
    }

    @Test
    void noPartFileLeftAfterWrite() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Path f = dir.resolve("b.bin");
        fs.write(f, new ByteArrayInputStream(new byte[10]), 10, () -> null, ChunkListener.NONE);
        assertTrue(Files.exists(f));
        assertFalse(Files.exists(dir.resolve("b.bin.part")));
    }

    @Test
    void sizeOfMissingFileThrowsNoSuchFile() {
        FileServiceImpl fs = new FileServiceImpl();
        assertThrows(NoSuchFileException.class, () -> fs.size(dir.resolve("nope")));
    }
}
