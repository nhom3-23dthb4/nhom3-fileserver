package vn.edu.fs;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.common.ChunkListener;
import vn.edu.fs.common.ErrorCode;
import vn.edu.fs.common.FsException;
import vn.edu.fs.storage.FileServiceImpl;
import vn.edu.fs.storage.LockManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceTest {

    @TempDir Path dir;

    /** InputStream cham: bao da bat dau roi dung doi, de giu khoa ghi. */
    private static final class SlowStream extends InputStream {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public int read() throws IOException {
            started.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return -1;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            return read();
        }
    }

    @Test
    void secondWriterGetsLockedWhileFirstIsWriting() throws Exception {
        FileServiceImpl fs = new FileServiceImpl(new LockManager(300));
        Path f = dir.resolve("shared.txt");
        SlowStream slow = new SlowStream();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            // Nguoi ghi thu nhat (luong phu) giu khoa, bi treo trong slow.read()
            Future<?> first = ex.submit(() -> {
                try {
                    fs.write(f, slow, 0, () -> "00", ChunkListener.NONE);
                } catch (Exception ignored) { }
                return null;
            });
            assertTrue(slow.started.await(5, TimeUnit.SECONDS));

            // Nguoi ghi thu hai (luong test) phai nhan LOCKED sau ~300 ms
            FsException e = assertThrows(FsException.class, () ->
                    fs.write(f, new ByteArrayInputStream(new byte[1]), 1, () -> "00", ChunkListener.NONE));
            assertEquals(ErrorCode.LOCKED, e.code());

            slow.release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            slow.release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    void readerIsBlockedWhileWriterHoldsLock() throws Exception {
        FileServiceImpl fs = new FileServiceImpl(new LockManager(300));
        Path f = dir.resolve("r.txt");
        Files.writeString(f, "abc");
        SlowStream slow = new SlowStream();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.submit(() -> {
                try { fs.write(f, slow, 0, () -> "00", ChunkListener.NONE); } catch (Exception ignored) { }
                return null;
            });
            assertTrue(slow.started.await(5, TimeUnit.SECONDS));
            FsException e = assertThrows(FsException.class, () ->
                    fs.read(f, new ByteArrayOutputStream(), ChunkListener.NONE));
            assertEquals(ErrorCode.LOCKED, e.code());
        } finally {
            slow.release.countDown();
            ex.shutdownNow();
        }
    }

    /** Chay voi -Xmx128m (cau hinh o pom.xml). 500 MB di qua server ma heap chi 128 MB. */
    @Test
    @Tag("heavy")
    @Timeout(600)
    void fiveHundredMegabytesWithSmallHeap() throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() < 400L * 1024 * 1024,
                "Chua chay voi -Xmx128m, test nay khong chung minh duoc gi");

        final long SIZE = 500L * 1024 * 1024;
        final MessageDigest genMd = MessageDigest.getInstance("SHA-256");
        final String[] expected = new String[1];

        InputStream gen = new InputStream() {
            long pos = 0;
            @Override public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : (one[0] & 0xFF);
            }
            @Override public int read(byte[] b, int off, int len) {
                if (pos >= SIZE) return -1;
                int n = (int) Math.min(len, SIZE - pos);
                for (int i = 0; i < n; i++) b[off + i] = (byte) ((pos + i) * 131);
                genMd.update(b, off, n);
                pos += n;
                return n;
            }
        };

        FileServiceImpl fs = new FileServiceImpl(new LockManager(), 1L << 30);
        Path f = dir.resolve("big.bin");
        fs.write(f, gen, SIZE, () -> {
            expected[0] = HexFormat.of().formatHex(genMd.digest());
            return expected[0];
        }, ChunkListener.NONE);

        assertEquals(SIZE, Files.size(f));

        // Doc lai, tinh SHA khi doc, khong giu du lieu
        final MessageDigest readMd = MessageDigest.getInstance("SHA-256");
        OutputStream sink = new OutputStream() {
            @Override public void write(int b) { readMd.update((byte) b); }
            @Override public void write(byte[] b, int off, int len) { readMd.update(b, off, len); }
        };
        fs.read(f, sink, ChunkListener.NONE);
        assertEquals(expected[0], HexFormat.of().formatHex(readMd.digest()));
    }
}
