package org.openpnp.machine.orion.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Single-rail transport for a plain USB-to-RS485 adapter. The adapter handles direction control,
 * so this just writes the frame and listens. An adapter that echoes TX back is tolerated: frames
 * identical to the request are dropped.
 */
public class SerialRs485Transport implements OrionTransport {
    private final SerialChannel channel;

    public SerialRs485Transport(SerialChannel channel) {
        this.channel = channel;
    }

    @Override
    public String describe() {
        return "USB-RS485 " + channel.describe();
    }

    @Override
    public int getRailCount() {
        return 1;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void open() throws OrionException {
        channel.open();
    }

    @Override
    public void close() {
        channel.close();
    }

    @Override
    public synchronized List<OrionFrame> exchange(int rail, OrionFrame request, int timeoutMs,
            int collectMs) throws OrionException {
        if (rail != 0) {
            throw new OrionException(OrionException.Kind.TRANSPORT,
                    "USB-RS485 adapter has a single rail (got rail " + rail + ")");
        }
        byte[] tx = request.encode();
        channel.flushInput();
        channel.write(tx);

        ByteArrayOutputStream rx = new ByteArrayOutputStream();
        byte[] buf = new byte[64];
        long start = System.nanoTime();
        long firstReplyAt = -1;
        List<OrionFrame> frames = new ArrayList<>();
        while (true) {
            long now = System.nanoTime();
            long elapsedMs = (now - start) / 1_000_000;
            long limit = firstReplyAt < 0 ? timeoutMs : (firstReplyAt + collectMs);
            if (elapsedMs >= limit) {
                break;
            }
            int n = channel.read(buf, (int) Math.min(50, Math.max(1, limit - elapsedMs)));
            if (n > 0) {
                rx.write(buf, 0, n);
                frames = parse(rx.toByteArray(), tx);
                if (!frames.isEmpty() && firstReplyAt < 0) {
                    firstReplyAt = (System.nanoTime() - start) / 1_000_000;
                    if (collectMs <= 0) {
                        break;
                    }
                }
            }
        }
        return frames;
    }

    static List<OrionFrame> parse(byte[] data, byte[] ownTx) {
        List<OrionFrame> out = new ArrayList<>();
        int i = 0;
        while (i < data.length) {
            java.util.Optional<OrionFrame> f = OrionFrame.decode(data, i);
            if (f.isPresent()) {
                byte[] enc = f.get().encode();
                if (!Arrays.equals(enc, ownTx)) {
                    out.add(f.get());
                }
                i += enc.length;
            } else {
                i++;
            }
        }
        return out;
    }
}
