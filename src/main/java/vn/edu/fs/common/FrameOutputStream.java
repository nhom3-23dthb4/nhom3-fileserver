package vn.edu.fs.common;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * OutputStream bao cac byte thanh khung DATA (moi khung toi da 32 KB), tinh SHA-256 tren duong di.
 * C ghi vao stream nay trong READ; sau do A goi finish() de gui khung END.
 */
public final class FrameOutputStream extends OutputStream {
    public static final int CHUNK = 32 * 1024;
    private final DataOutputStream out;
    private final byte[] buf = new byte[CHUNK];
    private final MessageDigest md;
    private int n;

    public FrameOutputStream(DataOutputStream out) {
        this(out, newSha256());
    }

    /** {@code seed} da chua SHA-256 cua phan dau file khong gui lai (resume): khung END se mang SHA cua CA file. */
    public FrameOutputStream(DataOutputStream out, MessageDigest seed) {
        this.out = out;
        this.md = seed;
    }

    public static MessageDigest newSha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * Stream chi cap nhat SHA-256, KHONG gui khung nao. Ghi phan dau file (da bo qua khi resume) vao day
     * TRUOC khi ghi bat ky byte nao vao stream chinh, de thu tu bam dung.
     */
    public OutputStream hashOnly() {
        return new OutputStream() {
            @Override public void write(int b) { md.update((byte) b); }
            @Override public void write(byte[] b, int off, int len) { md.update(b, off, len); }
        };
    }

    @Override public void write(int b) throws IOException {
        if (n == CHUNK) emit();
        buf[n++] = (byte) b;
    }

    @Override public void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            if (n == CHUNK) emit();
            int c = Math.min(len, CHUNK - n);
            System.arraycopy(b, off, buf, n, c);
            n += c; off += c; len -= c;
        }
    }

    private void emit() throws IOException {
        if (n == 0) return;
        FrameIO.write(out, Frame.DATA, buf, 0, n);
        md.update(buf, 0, n);
        n = 0;
    }

    /** Day not du lieu con lai, gui khung END (payload = SHA-256 hex) va flush. */
    public void finish() throws IOException {
        emit();
        byte[] sha = HexFormat.of().formatHex(md.digest()).getBytes(StandardCharsets.UTF_8);
        FrameIO.write(out, Frame.END, sha, 0, sha.length);
        out.flush();
    }
    // flush()/close() mac dinh: khong gui khung le, khong dong socket
}
