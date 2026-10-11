package vn.edu.fs.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

/** Doc/ghi khung goi tin. Dung readFully de khong doc thieu byte tren TCP. */
public final class FrameIO {
    private FrameIO() {}

    /** @return khung doc duoc, hoac null neu doi tac dong ket noi sach se (EOF truoc byte type). */
    public static Frame read(DataInputStream in) throws IOException {
        int type = in.read();
        if (type == -1) return null;
        int len = in.readInt();
        if (type < Frame.CONTROL || type > Frame.END)
            throw new ProtocolException("type khong hop le: " + type);
        if (len < 0 || len > Frame.MAX_PAYLOAD)          // chong can kiet RAM
            throw new ProtocolException("payloadLength khong hop le: " + len);
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Frame((byte) type, payload);
    }

    /** Ghi khung, KHONG flush (de ghi nhieu DATA lien tiep). */
    public static void write(DataOutputStream out, byte type, byte[] payload, int off, int len) throws IOException {
        if (len < 0 || len > Frame.MAX_PAYLOAD) throw new IllegalArgumentException("len=" + len);
        out.writeByte(type);
        out.writeInt(len);
        out.write(payload, off, len);
    }

    /** Ghi mot khung CONTROL (van ban UTF-8) roi flush. */
    public static void writeControl(DataOutputStream out, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        write(out, Frame.CONTROL, b, 0, b.length);
        out.flush();
    }
}
