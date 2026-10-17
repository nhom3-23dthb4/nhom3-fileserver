package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.fs.common.ErrorCode;
import vn.edu.fs.common.PathEscapeException;
import vn.edu.fs.storage.PathSandboxImpl;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Bo sung cho PathSandboxTest cua Nhan: them cac kieu tan cong path traversal pho bien. */
class TraversalTest {

    @TempDir Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {
            "/../secret.txt",
            "/../../etc/passwd",
            "/a/../../secret.txt",
            "/a/b/../../../secret.txt",
            "/..\\..\\windows\\win.ini",
            "..\\..\\windows\\win.ini",
            "../secret.txt",
            "etc/passwd",
            "C:\\Windows\\win.ini",
            "/C:/Windows/win.ini",
            "/a/\0/b"
    })
    void escapingPathsAreRejected(String evil) throws Exception {
        PathSandboxImpl sb = new PathSandboxImpl(Files.createDirectories(tmp.resolve("root")));
        PathEscapeException e = assertThrows(PathEscapeException.class, () -> sb.resolve(evil));
        assertEquals(ErrorCode.BAD_REQUEST, e.code());                 // PathEscapeException -> ma 400
    }

    @Test
    void normalPathsAreAccepted() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        PathSandboxImpl sb = new PathSandboxImpl(root);
        Path p = sb.resolve("/a/b/c.txt");
        assertTrue(p.startsWith(root.toRealPath()));
    }

    @Test
    void dotDotThatStaysInsideIsAllowed() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        PathSandboxImpl sb = new PathSandboxImpl(root);
        Path p = sb.resolve("/a/b/../c.txt");                         // = /a/c.txt, van trong goc
        assertEquals(root.toRealPath().resolve("a").resolve("c.txt"), p);
    }
}
