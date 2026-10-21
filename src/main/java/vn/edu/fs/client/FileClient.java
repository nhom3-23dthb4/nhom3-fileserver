package vn.edu.fs.client;

import vn.edu.fs.common.*;
import vn.edu.fs.server.TlsConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/** Client TLS 1.3. Moi ham gui MOT lenh va cho phan hoi; loi tu server nem FsException (co ma loi). */
public final class FileClient implements AutoCloseable {
    private static final int CHUNK = 32 * 1024;
    private final String host;
    private final int port;
    private final SSLContext ctx;
    private SSLSocket socket;
    private DataInputStream in;
    private DataOutputStream out;

    // Thong tin dang nhap giu trong bo nho de tu dang nhap lai khi server cat ket noi nhan roi.
    private String user;
    private char[] password;
    private int reconnects;
<<<<<<< HEAD

    private volatile Consumer<String> statusListener;                 // GUI hien thi "dang thu lai..."
    private volatile long retryDelayMs = 2_000;
    /** So lan thu lai toi da khi mat ket noi / server tat giua luc dang truyen file. */
    public static final int TRANSFER_RETRIES = 5;

    /** Kich thuoc va SHA-256 cua .part tren server. sha256 rong = server cu khong tra (khong doi chieu duoc). */
    public record RemotePart(long size, String sha256) {}
=======
>>>>>>> origin/main

    public FileClient(String host, int port, SSLContext ctx) throws IOException {
        this.host = host;
        this.port = port;
        this.ctx = ctx;
        open();
    }

    private void open() throws IOException {
        socket = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);
        socket.setEnabledProtocols(TlsConfig.PROTOCOLS);
        socket.setSoTimeout(60_000);
        socket.startHandshake();                                      // bat tay TLS ngay, loi thi biet lien
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
    }

    /** So lan client da tu ket noi lai va dang nhap lai (dung cho test va hien thi trang thai). */
    public int reconnectCount() { return reconnects; }

    private String readControl() throws IOException {
        Frame f = FrameIO.read(in);
        if (f == null) throw new EOFException("Server dong ket noi");
        if (f.type() != Frame.CONTROL) throw new ProtocolException("Mong doi khung CONTROL");
        return f.text();
    }

    /** Doc mot dong phan hoi; "ERR\t<ma>\t<noi dung>" -> nem FsException. Tra phan sau "OK\t". */
    private String ok(String line) throws IOException {
        if (line.startsWith("OK")) return line.length() > 3 ? line.substring(3) : "";
        String[] p = line.split("\t", 3);
        int code = p.length > 1 ? Integer.parseInt(p[1]) : 500;
        throw new FsException(ErrorCode.of(code), p.length > 2 ? p[2] : "Loi");
    }

    /** Gui mot lenh, cho phan hoi, khong thu lai. */
    private String send(String cmd) throws IOException {
        FrameIO.writeControl(out, cmd);
        return ok(readControl());
    }

    /**
     * Gui lenh. Neu ket noi da bi server cat (nhan roi qua han) thi tu ket noi lai, dang nhap lai
     * bang thong tin da luu roi gui lai dung lenh do mot lan. Loi nghiep vu (FsException), timeout
     * va loi giao thuc KHONG duoc thu lai.
     */
    private String request(String cmd) throws IOException {
        try {
            return send(cmd);
        } catch (IOException e) {
            if (!canReconnect(e)) throw e;
            reconnect();
            return send(cmd);
        }
    }

    private boolean canReconnect(IOException e) {
        return user != null
                && !(e instanceof FsException)
                && !(e instanceof java.net.SocketTimeoutException)
                && !(e instanceof ProtocolException);
    }

    private void reconnect() throws IOException {
        try { socket.close(); } catch (IOException ignore) { }
        open();
        send("LOGIN\t" + user + "\t" + new String(password));
        reconnects++;
    }

    public String login(String user, String password) throws IOException {
        String token = send("LOGIN\t" + user + "\t" + password);     // tra ve token
        this.user = user;                                              // chi luu khi dang nhap thanh cong
        this.password = password.toCharArray();
        return token;
    }

    public List<FileInfo> list(String path) throws IOException {
        int n = Integer.parseInt(request("LIST\t" + path));
        List<FileInfo> res = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String[] f = readControl().split("\t");
            res.add(new FileInfo(f[0], f[1].equals("D"), Long.parseLong(f[2]), Long.parseLong(f[3])));
        }
        return res;
    }

    public void mkdir(String path) throws IOException { request("MKDIR\t" + path); }
    public void delete(String path) throws IOException { request("DELETE\t" + path); }

    public void logout() throws IOException {
        user = null;                                                   // dang xuat chu dong: khong tu dang nhap lai
        send("LOGOUT");
    }

    // ------------------------------------------------------------------ tu thu lai khi mat ket noi giua chung

    /** Nhan thong bao trang thai (vd. "Mat ket noi, dang thu lai 2/5...") de GUI hien thi; co the null. */
    public void setStatusListener(Consumer<String> l) { this.statusListener = l; }

    /** Thoi gian cho giua cac lan thu lai (mac dinh 2 giay; test dat nho cho nhanh). */
    public void setRetryDelayMs(long ms) { this.retryDelayMs = ms; }

    private void status(String s) {
        Consumer<String> l = statusListener;
        if (l != null) l.accept(s);
    }

    @FunctionalInterface private interface Transfer { void run(boolean restart) throws IOException; }

    /**
     * Chay mot lan truyen file; neu ket noi dut giua chung (server tat/bat lai, mat mang) thi cho, ket noi lai, dang nhap lai
     * va chay lai: moi lan chay lai deu tu hoi .part de tiep tuc, khong gui lai tu dau. Loi nghiep vu (DENY, 404, 413...),
     * timeout va loi giao thuc khong duoc thu lai. Het so lan thu thi nem loi cuoi cung; .part van duoc giu.
     */
    private void withRetry(boolean restart, Transfer t) throws IOException {
        boolean fresh = restart;                                      // "tai lai tu dau" chi ap dung cho lan chay dau tien
        IOException last = null;
        for (int attempt = 0; attempt <= TRANSFER_RETRIES; attempt++) {
            try {
                t.run(fresh);
                return;
            } catch (IOException e) {
                if (!canReconnect(e)) throw e;
                last = e;
                fresh = false;
                if (attempt == TRANSFER_RETRIES) break;
                status("Mất kết nối, đang thử lại " + (attempt + 1) + "/" + TRANSFER_RETRIES + "...");
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                try {
                    reconnect();
                    status("Đã kết nối lại, tiếp tục truyền...");
                } catch (IOException re) {
                    last = re;                                        // server chua len lai: vong sau thu tiep
                }
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------ tai xuong (co tiep tuc)

    public void download(String path, Path target, ChunkListener l) throws IOException {
        download(path, target, l, false);
    }

    /**
     * Tai xuong vao {@code target}. Du lieu ghi vao {@code target.part}; file dich chi xuat hien khi tai xong va
     * dung SHA-256. Neu {@code target.part} con tu lan truoc (mat mang giua chung), chi tai phan con lai.
     * {@code restart} = true: bo .part cu, tai lai tu dau.
     */
    public void download(String path, Path target, ChunkListener l, boolean restart) throws IOException {
        withRetry(restart, fresh -> downloadOnce(path, target, l, fresh));
    }

    private void downloadOnce(String path, Path target, ChunkListener l, boolean restart) throws IOException {
        Path part = partOf(target);
        if (restart) Files.deleteIfExists(part);
        long offset = Files.isRegularFile(part) ? Files.size(part) : 0;
        try {
            downloadFrom(path, target, part, offset, l);
        } catch (FsException e) {
            if (offset > 0 && e.code() == ErrorCode.BAD_REQUEST) {    // .part cu khong dung (file doi / .part hong)
                Files.deleteIfExists(part);
                downloadFrom(path, target, part, 0, l);
            } else {
                if (e.code() == ErrorCode.NOT_FOUND) Files.deleteIfExists(part);
                throw e;
            }
        }
    }

    private void downloadFrom(String path, Path target, Path part, long offset, ChunkListener l) throws IOException {
        MessageDigest md = FrameOutputStream.newSha256();
        if (offset > 0) hashPrefix(part, offset, md);                 // SHA khoi dau tu phan da co
        long total = Long.parseLong(request(offset > 0 ? "READ\t" + path + "\t" + offset : "READ\t" + path));
        long got = offset;
        if (offset > 0) l.onProgress(got, total);
        try (OutputStream file = new BufferedOutputStream(offset > 0
                ? Files.newOutputStream(part, StandardOpenOption.APPEND)
                : Files.newOutputStream(part), CHUNK)) {
            while (true) {
                Frame f = FrameIO.read(in);
                if (f == null) throw new EOFException();              // mat ket noi: GIU .part de tiep tuc
                if (f.type() == Frame.DATA) {
                    file.write(f.payload()); md.update(f.payload()); got += f.payload().length;
                    l.onProgress(got, total);
                } else if (f.type() == Frame.END) {
                    file.close();
                    if (!HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(f.text())) {
                        Files.deleteIfExists(part);                   // du lieu hong: bo, khong giu de tiep tuc
                        throw new FsException(ErrorCode.BAD_REQUEST, "File tai ve bi hong (sai SHA-256)");
                    }
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } else {
                    ok(f.text());                                     // server bao loi giua chung
                }
            }
        }
    }

    // ------------------------------------------------------------------ tai len (co tiep tuc)

    /** Hoi server: ten.part cua {@code path} dang co bao nhieu byte (0 neu khong co / server cu khong ho tro). */
    public long remotePartSize(String path) throws IOException {
        try {
            return Long.parseLong(request("PARTSIZE\t" + path));
        } catch (FsException e) {
            if (e.code() == ErrorCode.BAD_REQUEST) return 0;          // server cu: khong biet lenh PARTSIZE
            throw e;                                                  // 403/409/...: bao nguyen nhan that
        }
    }

    /** Nhu {@link #remotePartSize} nhung kem SHA-256 cua .part (de biet no co khop file dinh gui khong). */
    public RemotePart remotePart(String path) throws IOException {
        try {
            String[] p = request("PARTSIZE\t" + path + "\tHASH").split("\t", -1);
            return new RemotePart(Long.parseLong(p[0]), p.length > 1 ? p[1] : "");
        } catch (FsException e) {
            if (e.code() == ErrorCode.BAD_REQUEST) return new RemotePart(0, "");
            throw e;
        }
    }

    public void upload(String path, Path source, ChunkListener l) throws IOException {
        upload(path, source, l, false);
    }

    /**
     * Tai len. Truoc tien hoi server da co bao nhieu byte (.part cua lan truoc bi dut) va SHA-256 cua phan do; neu khop
     * voi phan dau cua file nguon thi chi gui phan con lai, con khong khop thi gui lai tu dau NGAY (khong phi bang thong).
     * {@code restart} = true: bo qua .part tren server, gui lai tu dau.
     */
    public void upload(String path, Path source, ChunkListener l, boolean restart) throws IOException {
        withRetry(restart, fresh -> uploadOnce(path, source, l, fresh));
    }

    private void uploadOnce(String path, Path source, ChunkListener l, boolean restart) throws IOException {
        long size = Files.size(source);
        long offset = 0;
        MessageDigest md = FrameOutputStream.newSha256();
        if (!restart) {
            RemotePart rp = remotePart(path);
            offset = rp.size();
            if (offset > size) offset = 0;                            // .part lon hon file nguon: chac chan khong khop
            if (offset > 0) {
                hashPrefix(source, offset, md);                       // SHA phai phu CA file, ke ca phan khong gui lai
                if (!rp.sha256().isEmpty() && !rp.sha256().equalsIgnoreCase(hexOf(md))) {
                    offset = 0;                                       // .part la cua noi dung khac
                    md = FrameOutputStream.newSha256();
                }
            }
        }
        try {
            uploadFrom(path, source, size, offset, md, l);
        } catch (FsException e) {
            boolean stale = e.code() == ErrorCode.BAD_REQUEST || e.code() == ErrorCode.TOO_LARGE;
            if (offset > 0 && stale) uploadFrom(path, source, size, 0, FrameOutputStream.newSha256(), l);   // tu dau, 1 lan duy nhat
            else throw e;
        }
    }

    private void uploadFrom(String path, Path source, long size, long offset, MessageDigest md, ChunkListener l) throws IOException {
        request(offset > 0 ? "WRITE\t" + path + "\t" + size + "\t" + offset
                           : "WRITE\t" + path + "\t" + size);        // loi (DENY, 413...) o day thi chua gui du lieu
        FrameOutputStream fos = new FrameOutputStream(out, md);
        byte[] buf = new byte[CHUNK];
        long sent = offset; int n;
        if (offset > 0) l.onProgress(sent, size);
        try (InputStream file = new BufferedInputStream(Files.newInputStream(source), CHUNK)) {
            file.skipNBytes(offset);
            while ((n = file.read(buf)) != -1) {
                fos.write(buf, 0, n);
                sent += n;
                l.onProgress(sent, size);
            }
        }
        fos.finish();
        ok(readControl());                                            // ket qua cuoi cung cua WRITE
    }

<<<<<<< HEAD
    /** Duong dan file tam {@code ten.part} canh file dich (de GUI biet co ban tai xuong dang do khong). */
    public static Path partFile(Path target) { return partOf(target); }

    /** SHA-256 hex cua du lieu da bam, KHONG lam mat trang thai cua md. */
    private static String hexOf(MessageDigest md) {
        try {
            return HexFormat.of().formatHex(((MessageDigest) md.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path partOf(Path file) { return file.resolveSibling(file.getFileName() + ".part"); }

    /** Bam {@code count} byte dau cua file vao md (neu file ngan hon -> EOFException). */
    private static void hashPrefix(Path f, long count, MessageDigest md) throws IOException {
        try (InputStream is = new BufferedInputStream(Files.newInputStream(f), CHUNK)) {
            byte[] buf = new byte[CHUNK];
            long left = count;
            while (left > 0) {
                int n = is.read(buf, 0, (int) Math.min(buf.length, left));
                if (n == -1) throw new EOFException("File ngan hon offset");
                md.update(buf, 0, n);
                left -= n;
            }
        }
    }

=======
>>>>>>> origin/main
    @Override public void close() throws IOException {
        user = null;
        if (password != null) { java.util.Arrays.fill(password, '\0'); password = null; }
        socket.close();
    }
<<<<<<< HEAD
}
=======
}
>>>>>>> origin/main
