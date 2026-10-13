package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.common.PathEscapeException;
import vn.edu.fs.storage.PathSandboxImpl;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathSandboxTest {
    @TempDir Path tmp;

    @Test void normalPathsStayInside() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        PathSandboxImpl sb = new PathSandboxImpl(root);
        Path p = sb.resolve("/a/b.txt");
        assertTrue(p.startsWith(root.toRealPath()));
        assertEquals("/a/b.txt", sb.toVirtual(p));
        assertEquals("/", sb.toVirtual(sb.resolve("/")));
        assertEquals("/a", sb.toVirtual(sb.resolve("/a/./b/..")));                 // da chuan hoa
    }

    @Test void traversalAndWeirdInputsBlocked() throws Exception {
        PathSandboxImpl sb = new PathSandboxImpl(Files.createDirectories(tmp.resolve("root")));
        for (String bad : new String[]{"/..", "/../x", "/a/../../x", "x/y", "", "/a\\b", "/a:b", "/a\0b"})
            assertThrows(PathEscapeException.class, () -> sb.resolve(bad), bad);
        assertThrows(PathEscapeException.class, () -> sb.resolve(null));
    }

    @Test void symlinkPointingOutsideIsBlocked() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        try { Files.createSymbolicLink(root.resolve("link"), outside); }
        catch (Exception e) { return; }                                             // Windows khong cho tao symlink thi bo qua
        PathSandboxImpl sb = new PathSandboxImpl(root);
        assertThrows(PathEscapeException.class, () -> sb.resolve("/link/secret.txt"));
    }
}