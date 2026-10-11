package vn.edu.fs.common;

@FunctionalInterface
public interface ChunkListener {
    ChunkListener NONE = (done, total) -> {};
    void onProgress(long done, long total);
}
