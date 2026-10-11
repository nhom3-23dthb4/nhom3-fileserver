package vn.edu.fs.dev;

import vn.edu.fs.common.PathEscapeException;
import vn.edu.fs.common.PathSandbox;

import java.nio.file.Path;

/** TAM THOI. C se thay bang ban day du (symlink, ky tu la...). */
public final class DevSandbox implements PathSandbox {
    private final Path root;
    public DevSandbox(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override public Path resolve(String v) throws PathEscapeException {
        if (v.isEmpty() || v.indexOf('\0') >= 0 || v.indexOf('\\') >= 0 || v.charAt(0) != '/')
            throw new PathEscapeException(v);
        Path p = root.resolve(v.substring(1)).normalize();
        if (!p.startsWith(root)) throw new PathEscapeException(v);
        return p;
    }
    @Override public String toVirtual(Path real) {
        String rel = root.relativize(real).toString().replace('\\', '/');
        return "/" + rel;
    }
}
