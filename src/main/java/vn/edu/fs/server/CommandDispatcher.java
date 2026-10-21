package vn.edu.fs.server;

import vn.edu.fs.common.*;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Dieu phoi MOT lenh theo dung 7 buoc: dang nhap -> sandbox -> ACL -> (khoa+I/O trong FileService) -> log.
 * Dispatcher khong tu kiem tra quyen, khong tu thao tac dia.
 */
final class CommandDispatcher {
    static final int MAX_PATH = 4096;
    static final int MAX_FAILED_LOGINS = 5;

    private final AuthService auth;
    private final AclService acl;
    private final PathSandbox sandbox;
    private final FileService files;
    private final AuditLogger audit;
    private final long maxFileBytes;

    CommandDispatcher(AuthService auth, AclService acl, PathSandbox sandbox, FileService files,
                      AuditLogger audit, long maxFileBytes) {
        this.auth = auth; this.acl = acl; this.sandbox = sandbox; this.files = files;
        this.audit = audit; this.maxFileBytes = maxFileBytes;
    }

    /** @return false neu phai dong ket noi. Nem IOException neu ket noi hong. */
    boolean dispatch(Connection c, String line) throws IOException {
        String[] f = line.split("\t", -1);
        String cmd = f[0].toUpperCase();
        try {
            switch (cmd) {
                case "LOGIN":  return login(c, f);
                case "LOGOUT": return logout(c);
                case "LIST": case "READ": case "WRITE": case "PARTSIZE": case "MKDIR": case "DELETE":
                    break;
                default:
                    return err(c, cmd, "-", ErrorCode.BAD_REQUEST, "Lenh khong ho tro");
            }
            // Buoc 2: da dang nhap chua?
            if (c.session == null) return err(c, cmd, f.length > 1 ? f[1] : "-", ErrorCode.UNAUTHORIZED, "Chua dang nhap");
            if (f.length < 2 || f[1].length() > MAX_PATH)
                return err(c, cmd, "-", ErrorCode.BAD_REQUEST, "Thieu hoac sai duong dan");
            // Buoc 3: chuan hoa duong dan trong sandbox, roi lay DUONG DAN AO CHUAN HOA cho ACL
            Path real = sandbox.resolve(f[1]);
            String vpath = sandbox.toVirtual(real);
            // Buoc 4: ACL
            Permission need = switch (cmd) {
                case "LIST" -> Permission.LIST;
                case "READ" -> Permission.READ;
                case "DELETE" -> Permission.DELETE;
                default -> Permission.WRITE;     // WRITE, PARTSIZE, MKDIR
            };
            if (!acl.isAllowed(c.session.userId(), vpath, need)) {
                audit.log(c.user(), c.remote, cmd, vpath, "DENY");
                // WRITE chua gui OK san sang nen client chua gui du lieu -> khong can doc them
                reply(c, "ERR\t403\tDENY: khong co quyen " + need);
                return true;
            }
            // Buoc 5-6: khoa + I/O nam trong FileService
            return switch (cmd) {
                case "LIST" -> list(c, real, vpath);
                case "READ" -> read(c, real, vpath, f);
                case "WRITE" -> write(c, real, vpath, f);
                case "PARTSIZE" -> partSize(c, real, vpath, f);
                case "MKDIR" -> simple(c, cmd, vpath, () -> files.mkdir(real));
                default -> simple(c, cmd, vpath, () -> files.delete(real));
            };
        } catch (FsException e) {
            return err(c, cmd, f.length > 1 ? f[1] : "-", e.code(), e.getMessage());
        } catch (NoSuchFileException e) {
            return err(c, cmd, f.length > 1 ? f[1] : "-", ErrorCode.NOT_FOUND, "Khong ton tai");
        } catch (ProtocolException | EOFException e) {
            throw e;                         // ket noi hong -> ClientHandler dong
        } catch (IOException e) {
            return err(c, cmd, f.length > 1 ? f[1] : "-", ErrorCode.INTERNAL, "Loi may chu");
        }
    }

    private boolean login(Connection c, String[] f) throws IOException {
        if (f.length < 3) return err(c, "LOGIN", "-", ErrorCode.BAD_REQUEST, "Cu phap: LOGIN user password");
        Optional<Session> s = auth.login(f[1], f[2]);
        if (s.isEmpty()) {
            c.failedLogins++;
            audit.log(f[1], c.remote, "LOGIN", "-", "ERR 401");
            reply(c, "ERR\t401\tSai tai khoan hoac mat khau");
            return c.failedLogins < MAX_FAILED_LOGINS;   // qua 5 lan sai tren mot ket noi -> cat
        }
        c.session = s.get();
        audit.log(c.user(), c.remote, "LOGIN", "-", "OK");
        reply(c, "OK\t" + c.session.token());
        return true;
    }

    private boolean logout(Connection c) throws IOException {
        audit.log(c.user(), c.remote, "LOGOUT", "-", "OK");
        reply(c, "OK");
        return false;
    }

    private boolean list(Connection c, Path real, String vpath) throws IOException {
        List<FileInfo> items = files.list(real);
        reply(c, "OK\t" + items.size());
        for (FileInfo i : items) {
            byte[] row = (i.name() + "\t" + (i.directory() ? "D" : "F") + "\t" + i.size() + "\t" + i.mtimeMillis())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            FrameIO.write(c.out, Frame.CONTROL, row, 0, row.length);
        }
        c.out.flush();
        audit.log(c.user(), c.remote, "LIST", vpath, "OK");
        return true;
    }

    /** READ path [offset]: offset = so byte client da co (tiep tuc tai xuong). Khung END mang SHA-256 cua CA file. */
    private boolean read(Connection c, Path real, String vpath, String[] f) throws IOException {
        long offset = parseOffset(f, 2);
        long size = files.size(real);                    // 404 / 400 neu khong phai file
        if (offset < 0 || offset > size) return err(c, "READ", vpath, ErrorCode.BAD_REQUEST, "Offset khong hop le");
        reply(c, "OK\t" + size);
        FrameOutputStream fos = new FrameOutputStream(c.out);
        try {
            files.read(real, offset, fos, fos.hashOnly(), ChunkListener.NONE);
            fos.finish();
            audit.log(c.user(), c.remote, "READ", vpath, "OK");
        } catch (FsException e) {
            // da gui "OK size": bao loi bang khung CONTROL "ERR" thay cho khung END
            audit.log(c.user(), c.remote, "READ", vpath, "ERR " + e.code().code);
            reply(c, "ERR\t" + e.code().code + "\t" + e.getMessage());
        }
        return true;
    }

    /**
     * PARTSIZE path [HASH] -> "OK\t<so byte .part dang co>" (0 = chua co gi). Can quyen WRITE nhu WRITE.
     * Co tham so HASH -> them cot thu hai: SHA-256 cua .part ("" neu chua co) de client doi chieu voi file nguon.
     */
    private boolean partSize(Connection c, Path real, String vpath, String[] f) throws IOException {
        long n = files.partSize(real);
        boolean withHash = f.length > 2 && "HASH".equalsIgnoreCase(f[2]);
        String sha = withHash && n > 0 ? files.partSha256(real) : "";
        audit.log(c.user(), c.remote, "PARTSIZE", vpath, "OK");
        reply(c, withHash ? "OK\t" + n + "\t" + sha : "OK\t" + n);
        return true;
    }

    /** Tham so so nguyen tuy chon o vi tri idx: khong co/rong -> 0; khong phai so -> -1. */
    private static long parseOffset(String[] f, int idx) {
        if (f.length <= idx || f[idx].isEmpty()) return 0;
        try { return Long.parseLong(f[idx]); }
        catch (NumberFormatException e) { return -1; }
    }

    /** WRITE path size [offset]: offset &gt; 0 = noi tiep vao .part da co (phai dung offset byte). */
    private boolean write(Connection c, Path real, String vpath, String[] f) throws IOException {
        long size;
        try { size = Long.parseLong(f.length > 2 ? f[2] : ""); }
        catch (NumberFormatException e) { size = -1; }
        if (size < 0) return err(c, "WRITE", vpath, ErrorCode.BAD_REQUEST, "Kich thuoc khong hop le");
        long offset = parseOffset(f, 3);
        if (offset < 0 || offset > size) return err(c, "WRITE", vpath, ErrorCode.BAD_REQUEST, "Offset khong hop le");
        if (size > maxFileBytes) return err(c, "WRITE", vpath, ErrorCode.TOO_LARGE, "File qua lon");
        if (offset > 0 && files.partSize(real) != offset) {   // bao loi SOM, truoc khi client gui du lieu
            return err(c, "WRITE", vpath, ErrorCode.BAD_REQUEST, "Khong tiep tuc duoc: .part khong khop offset");
        }
        reply(c, "OK");                                  // san sang nhan du lieu
        FrameInputStream fin = new FrameInputStream(c.in, size - offset);
        try {
            files.write(real, fin, offset, size, fin::expectedSha256, ChunkListener.NONE);
            if (!fin.ended()) fin.drain(8192);
            audit.log(c.user(), c.remote, "WRITE", vpath, "OK");
            reply(c, "OK");
        } catch (ProtocolException | EOFException e) {
            throw e;                                     // client hong giao thuc / ngat -> dong ket noi (giu .part)
        } catch (FsException | NoSuchFileException e) {
            // Client da bat dau gui DATA: phai doc het toi khung END roi moi tra loi loi
            if (!fin.ended()) fin.drain(8192);
            ErrorCode code = e instanceof FsException fe ? fe.code() : ErrorCode.NOT_FOUND;
            audit.log(c.user(), c.remote, "WRITE", vpath, "ERR " + code.code);
            reply(c, "ERR\t" + code.code + "\t" + (e instanceof FsException ? e.getMessage() : "Khong ton tai"));
        }
        return true;
    }

    private interface IoAction { void run() throws IOException; }

    private boolean simple(Connection c, String cmd, String vpath, IoAction a) throws IOException {
        a.run();
        audit.log(c.user(), c.remote, cmd, vpath, "OK");
        reply(c, "OK");
        return true;
    }

    private boolean err(Connection c, String cmd, String path, ErrorCode code, String msg) throws IOException {
        audit.log(c.user(), c.remote, cmd, path, "ERR " + code.code);
        reply(c, "ERR\t" + code.code + "\t" + msg);
        return true;
    }

    private static void reply(Connection c, String text) throws IOException { FrameIO.writeControl(c.out, text); }
}
