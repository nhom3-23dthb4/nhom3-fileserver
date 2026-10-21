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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

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

    public void download(String path, Path target, ChunkListener l) throws IOException {
        long total = Long.parseLong(request("READ\t" + path));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long got = 0;
            try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(target), CHUNK)) {
                while (true) {
                    Frame f = FrameIO.read(in);
                    if (f == null) throw new EOFException();
                    if (f.type() == Frame.DATA) {
                        file.write(f.payload()); md.update(f.payload()); got += f.payload().length;
                        l.onProgress(got, total);
                    } else if (f.type() == Frame.END) {
                        if (!HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(f.text()))
                            throw new FsException(ErrorCode.BAD_REQUEST, "File tai ve bi hong (sai SHA-256)");
                        return;
                    } else {
                        ok(f.text());                                 // server bao loi giua chung
                    }
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } catch (IOException e) {
            Files.deleteIfExists(target);                             // khong de lai file do dang
            throw e;
        }
    }

    public void upload(String path, Path source, ChunkListener l) throws IOException {
        long size = Files.size(source);
        request("WRITE\t" + path + "\t" + size);                      // loi (DENY, 413...) o day thi chua gui du lieu
        FrameOutputStream fos = new FrameOutputStream(out);
        byte[] buf = new byte[CHUNK];
        long sent = 0; int n;
        try (InputStream file = new BufferedInputStream(Files.newInputStream(source), CHUNK)) {
            while ((n = file.read(buf)) != -1) {
                fos.write(buf, 0, n);
                sent += n;
                l.onProgress(sent, size);
            }
        }
        fos.finish();
        ok(readControl());                                            // ket qua cuoi cung cua WRITE
    }

    @Override public void close() throws IOException {
        user = null;
        if (password != null) { java.util.Arrays.fill(password, '\0'); password = null; }
        socket.close();
    }
}
