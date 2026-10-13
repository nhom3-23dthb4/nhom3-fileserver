package vn.edu.fs.storage;

import vn.edu.fs.common.PathEscapeException;
import vn.edu.fs.common.PathSandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Co lap moi duong dan trong thu muc goc. Chan "..", ky tu la va symlink tro ra ngoai. */
public final class PathSandboxImpl implements PathSandbox {
    private final Path root;

    public PathSandboxImpl(Path rootDir) throws IOException {
        Files.createDirectories(rootDir);
        this.root = rootDir.toRealPath();
    }

    @Override public Path resolve(String v) throws PathEscapeException {
        if (v == null || v.isEmpty() || v.charAt(0) != '/'
                || v.indexOf('\0') >= 0 || v.indexOf('\\') >= 0 || v.indexOf(':') >= 0)
            throw new PathEscapeException(v);
        Path p = root.resolve(v.substring(1)).normalize();
        if (!p.startsWith(root)) throw new PathEscapeException(v);
        try {                                   // symlink: kiem tra thu muc cha da ton tai gan nhat
            Path a = p;
            while (a != null && !Files.exists(a)) a = a.getParent();
            if (a != null && !a.toRealPath().startsWith(root)) throw new PathEscapeException(v);
        } catch (IOException e) {
            throw new PathEscapeException(v);
        }
        return p;
    }

    @Override public String toVirtual(Path real) {
        String rel = root.relativize(real.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return "/" + rel;
    }
}