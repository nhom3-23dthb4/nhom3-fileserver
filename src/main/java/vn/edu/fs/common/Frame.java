package vn.edu.fs.common;

import java.nio.charset.StandardCharsets;

/** Mot khung goi tin: 1 byte type + 4 byte do dai + payload. */
public record Frame(byte type, byte[] payload) {
    public static final byte CONTROL = 0x01;
    public static final byte DATA = 0x02;
    public static final byte END = 0x03;
    public static final int MAX_PAYLOAD = 65536;

    public String text() { return new String(payload, StandardCharsets.UTF_8); }
}
