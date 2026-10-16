package vn.edu.fs;

import org.junit.jupiter.api.Test;
import vn.edu.fs.common.ErrorCode;
import vn.edu.fs.common.FsException;
import vn.edu.fs.storage.LockManager;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LockManagerTest {

    private final Path f = Path.of("data", "a.txt");

    /** Goi lock o mot luong khac va tra ve ma loi (null neu lay duoc). */
    private ErrorCode tryOther(ExecutorService ex, LockManager m, boolean write) throws Exception {
        return ex.submit(() -> {
            try (LockManager.Held h = write ? m.write(f) : m.read(f)) {
                return (ErrorCode) null;
            } catch (FsException e) {
                return e.code();
            }
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void twoReadersAtSameTimeAreAllowed() throws Exception {
        LockManager m = new LockManager(300);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (LockManager.Held r1 = m.read(f)) {
            assertNull(tryOther(ex, m, false));          // reader thu hai vao duoc
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void writerIsBlockedByReaderAndTimesOut() throws Exception {
        LockManager m = new LockManager(300);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (LockManager.Held r = m.read(f)) {
            assertEquals(ErrorCode.LOCKED, tryOther(ex, m, true));
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void readerIsBlockedByWriter() throws Exception {
        LockManager m = new LockManager(300);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (LockManager.Held w = m.write(f)) {
            assertEquals(ErrorCode.LOCKED, tryOther(ex, m, false));
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void canLockAgainAfterRelease() throws Exception {
        LockManager m = new LockManager(300);
        try (LockManager.Held w = m.write(f)) { }
        try (LockManager.Held w2 = m.write(f)) { }       // khong nem loi
    }

    @Test
    void samePathWrittenDifferentlyIsSameLock() throws Exception {
        LockManager m = new LockManager(300);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (LockManager.Held w = m.write(Path.of("data", "x", "..", "a.txt"))) {
            assertEquals(ErrorCode.LOCKED, tryOther(ex, m, true));
        } finally {
            ex.shutdownNow();
        }
    }
}
