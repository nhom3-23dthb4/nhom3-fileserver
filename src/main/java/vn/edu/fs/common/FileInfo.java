package vn.edu.fs.common;

public record FileInfo(String name, boolean directory, long size, long mtimeMillis) {}
