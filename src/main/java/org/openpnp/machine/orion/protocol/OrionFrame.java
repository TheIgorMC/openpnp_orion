package org.openpnp.machine.orion.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * One Orion RS485 frame: [0xAA][ADDR][CMD][LEN][PAYLOAD...][CRC8].
 * CRC8 uses polynomial 0x07 over ADDR..PAYLOAD (the start byte is not covered).
 */
public final class OrionFrame {
    public static final int START = 0xAA;
    public static final int MAX_PAYLOAD = 16;

    public final int address;
    public final int command;
    public final byte[] payload;

    public OrionFrame(int address, int command, byte... payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Payload too long: " + payload.length);
        }
        this.address = address & 0xFF;
        this.command = command & 0xFF;
        this.payload = payload.clone();
    }

    public static int crc8(byte[] data, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    public byte[] encode() {
        byte[] out = new byte[5 + payload.length];
        out[0] = (byte) START;
        out[1] = (byte) address;
        out[2] = (byte) command;
        out[3] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 4, payload.length);
        out[4 + payload.length] = (byte) crc8(out, 1, 4 + payload.length);
        return out;
    }

    public String toHex() {
        return toHex(encode());
    }

    /**
     * Try to parse one frame starting at offset. Returns empty when the bytes there are not
     * (yet) a complete valid frame.
     */
    public static Optional<OrionFrame> decode(byte[] data, int offset) {
        if (data.length - offset < 5 || (data[offset] & 0xFF) != START) {
            return Optional.empty();
        }
        int len = data[offset + 3] & 0xFF;
        if (len > MAX_PAYLOAD || data.length - offset < 5 + len) {
            return Optional.empty();
        }
        int crc = crc8(data, offset + 1, offset + 4 + len);
        if (crc != (data[offset + 4 + len] & 0xFF)) {
            return Optional.empty();
        }
        return Optional.of(new OrionFrame(data[offset + 1], data[offset + 2],
                Arrays.copyOfRange(data, offset + 4, offset + 4 + len)));
    }

    public static Optional<OrionFrame> decode(byte[] data) {
        return decode(data, 0);
    }

    /** Scan a byte stream for the first valid frame, skipping garbage (and our own echo). */
    public static Optional<OrionFrame> findFirst(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            Optional<OrionFrame> f = decode(data, i);
            if (f.isPresent()) {
                return f;
            }
        }
        return Optional.empty();
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        String h = hex.replaceAll("\\s+", "");
        if (h.length() % 2 != 0) {
            throw new IllegalArgumentException("Odd-length hex string: " + hex);
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    public int u8(int i) {
        return payload[i] & 0xFF;
    }

    public int u16(int i) {
        return ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
    }

    public boolean isAck() {
        return command == OrionCommand.ACK;
    }

    public boolean isNack() {
        return command == OrionCommand.NACK;
    }

    @Override
    public String toString() {
        return String.format("addr=%d cmd=0x%02X [%s]", address, command, toHex(payload));
    }
}
