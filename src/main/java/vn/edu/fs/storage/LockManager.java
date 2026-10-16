package vn.edu.fs.storage;

import vn.edu.fs.common.ErrorCode;
import vn.edu.fs.common.FsException;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Khoa doc/ghi theo tung file. Nhieu reader song song; writer doc quyen; cho qua timeout thi LOCKED (409). */
public final class LockManager {

    /** Mot khoa + so nguoi dang dung/cho no. */
    private static final class Entry {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        int refs;
    }

    /** Tay cam khoa; dung trong try-with-resources de tu nha khoa. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    private final Map<Path, Entry> map = new HashMap<>();
    private final long timeoutMs;

    public LockManager(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public LockManager() {
        this(5_000);
    }

    public Held read(Path file) throws FsException {
        return acquire(file, false);
    }

    public Held write(Path file) throws FsException {
        return acquire(file, true);
    }

    private Held acquire(Path file, boolean write) throws FsException {
        Path key = file.toAbsolutePath().normalize();
        Entry e;
        synchronized (map) {
            e = map.computeIfAbsent(key, k -> new Entry());
            e.refs++;
        }
        Lock l = write ? e.lock.writeLock() : e.lock.readLock();
        boolean ok = false;
        try {
            ok = l.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (!ok) {
            release(key, e);
            throw new FsException(ErrorCode.LOCKED, "File dang duoc nguoi khac su dung");
        }
        return () -> {
            l.unlock();
            release(key, e);
        };
    }

    private void release(Path key, Entry e) {
        synchronized (map) {
            if (--e.refs == 0) {
                map.remove(key);
            }
        }
    }
}
