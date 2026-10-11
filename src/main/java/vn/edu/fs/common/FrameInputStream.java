package vn.edu.fs.common;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;

/**
 * InputStream doc cac khung DATA cho den khung END (tra -1). Khong gom file vao RAM.
 * Neu client gui qua {@code limit} byte -> FsException 413.
 */
public final class FrameInputStream extends InputStream {
    private final DataInputStream in;
    private final long limit;
    private byte[] cur;
    private int pos;
    private boolean ended;
    private String sha;
    private long total;

    public FrameInputStream(DataInputStream in, long limit) { this.in = in; this.limit = limit; }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int r = read(one, 0, 1);
        return r == -1 ? -1 : (one[0] & 0xFF);
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (cur == null || pos >= cur.length) {
            if (ended) return -1;
            Frame f = FrameIO.read(in);
            if (f == null) throw new EOFException("Client ngat ket noi giua luc upload");
            if (f.type() == Frame.DATA) { cur = f.payload(); pos = 0; }
            else if (f.type() == Frame.END) { ended = true; sha = f.text(); return -1; }
            else throw new ProtocolException("Khung CONTROL bat ngo trong luc upload");
        }
        int c = Math.min(len, cur.length - pos);
        System.arraycopy(cur, pos, b, off, c);
        pos += c;
        total += c;
        if (total > limit) throw new FsException(ErrorCode.TOO_LARGE, "Du lieu vuot kich thuoc da khai bao");
        return c;
    }

    public boolean ended() { return ended; }
    /** SHA-256 hex do client khai bao o khung END; chi hop le sau khi doc het (read tra -1). */
    public String expectedSha256() { return sha; }

    /** Doc bo phan con lai den khung END, toi da maxFrames khung (chong treo server). */
    public void drain(int maxFrames) throws IOException {
        for (int i = 0; i < maxFrames && !ended; i++) {
            Frame f = FrameIO.read(in);
            if (f == null) throw new EOFException();
            if (f.type() == Frame.END) ended = true;
            else if (f.type() != Frame.DATA) throw new ProtocolException("Khung CONTROL bat ngo");
        }
        if (!ended) throw new ProtocolException("Qua nhieu khung du khi huy upload");
    }
}
