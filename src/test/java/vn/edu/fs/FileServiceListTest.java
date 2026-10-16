package vn.edu.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.fs.common.ErrorCode;
import vn.edu.fs.common.FileInfo;
import vn.edu.fs.common.FsException;
import vn.edu.fs.storage.FileServiceImpl;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceListTest {
    @TempDir Path tmp;

    @Test void listMkdirDelete() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        Files.writeString(tmp.resolve("b.txt"), "xyz");
        Files.writeString(tmp.resolve("a.txt.part"), "dang ghi");               // file tam: phai bi an
        fs.mkdir(tmp.resolve("dir"));
        List<FileInfo> l = fs.list(tmp);
        assertEquals(2, l.size());
        assertEquals("b.txt", l.get(0).name());                                  // sap xep theo ten
        assertEquals(3, l.get(0).size());
        assertTrue(l.get(1).directory());
        fs.delete(tmp.resolve("dir"));
        assertEquals(1, fs.list(tmp).size());
    }

    @Test void errorsHaveRightCodes() throws Exception {
        FileServiceImpl fs = new FileServiceImpl();
        fs.mkdir(tmp.resolve("d"));
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(FsException.class, () -> fs.mkdir(tmp.resolve("d"))).code());   // da ton tai
        Files.writeString(tmp.resolve("d/f"), "1");
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(FsException.class, () -> fs.delete(tmp.resolve("d"))).code());  // khong rong
        assertThrows(NoSuchFileException.class, () -> fs.list(tmp.resolve("khong-co")));
        assertThrows(NoSuchFileException.class, () -> fs.delete(tmp.resolve("khong-co")));
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(FsException.class, () -> fs.list(tmp.resolve("d/f"))).code()); // khong phai thu muc
    }
}