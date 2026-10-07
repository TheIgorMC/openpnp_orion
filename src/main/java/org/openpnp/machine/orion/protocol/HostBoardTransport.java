package org.openpnp.machine.orion.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Transport for the OrionPnP host board: two (or more) isolated RS485 rails behind one USB CDC
 * port, driven by a plain text line protocol (115200, one command per line, '\n' terminated):
 *
 * <pre>
 *   VER                                  -> OK orion-host &lt;version&gt; rails=&lt;n&gt;
 *   RS485 &lt;rail&gt; &lt;hexframe&gt; &lt;timeoutMs&gt; &lt;collectMs&gt;
 *                                        -> RX &lt;hexframe&gt;   (zero or more)
 *                                           OK                 (or ERR &lt;text&gt;)
 *   RAIL &lt;rail&gt; ON|OFF                  -> OK | ERR &lt;text&gt;
 *   RAIL &lt;rail&gt; ?                       -> OK ON|OFF [mA=&lt;n&gt;] [FAULT]
 * </pre>
 * A reply that simply never arrives is reported as "OK" with no RX lines (timeout).
 */
public class HostBoardTransport implements OrionTransport {
    private final SerialChannel channel;
    private int rails = 2;
    private String version = "?";
    private final StringBuilder lineBuf = new StringBuilder();

    public HostBoardTransport(SerialChannel channel) {
        this.channel = channel;
    }

    @Override
    public String describe() {
        return "Host board " + channel.describe() + " (fw " + version + ")";
    }

    @Override
    public int getRailCount() {
        return rails;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public synchronized void open() throws OrionException {
        channel.open();
        List<String> lines = command("VER", 1000);
        String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        if (!last.startsWith("OK")) {
            channel.close();
            throw new OrionException(OrionException.Kind.TRANSPORT,
                    "Host board did not answer VER (got: " + last + ")");
        }
        for (String tok : last.split("\\s+")) {
            if (tok.startsWith("rails=")) {
                try {
                    rails = Integer.parseInt(tok.substring(6));
                } catch (NumberFormatException ignored) {
                }
            } else if (tok.matches("v?\\d+(\\.\\d+)*\\w*")) {
                version = tok;
            }
        }
    }

    @Override
    public void close() {
        channel.close();
    }

    @Override
    public synchronized List<OrionFrame> exchange(int rail, OrionFrame request, int timeoutMs,
            int collectMs) throws OrionException {
        if (rail < 0 || rail >= rails) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "Rail " + rail + " does not exist");
        }
        String cmd = String.format("RS485 %d %s %d %d", rail, request.toHex(), timeoutMs, collectMs);
        List<String> lines = command(cmd, timeoutMs + collectMs + 1000);
        List<OrionFrame> frames = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("RX ")) {
                try {
                    OrionFrame.decode(OrionFrame.fromHex(l.substring(3))).ifPresent(frames::add);
                } catch (IllegalArgumentException ignored) {
                    // garbled line, treat as no reply
                }
            }
        }
        return frames;
    }

    @Override
    public synchronized boolean setRailPower(int rail, boolean on) throws OrionException {
        command(String.format("RAIL %d %s", rail, on ? "ON" : "OFF"), 2000);
        return true;
    }

    /** Query rail state line, e.g. "OK ON mA=120". */
    public synchronized String queryRail(int rail) throws OrionException {
        List<String> lines = command("RAIL " + rail + " ?", 1000);
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }

    /** Send a line, collect lines up to and including the terminating OK/ERR. Throws on ERR. */
    private List<String> command(String line, int timeoutMs) throws OrionException {
        channel.flushInput();
        lineBuf.setLength(0);
        channel.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        List<String> lines = new ArrayList<>();
        byte[] buf = new byte[256];
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            int n = channel.read(buf, 50);
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == '\n') {
                    String l = lineBuf.toString().trim();
                    lineBuf.setLength(0);
                    if (l.isEmpty()) {
                        continue;
                    }
                    lines.add(l);
                    if (l.startsWith("OK")) {
                        return lines;
                    }
                    if (l.startsWith("ERR")) {
                        throw new OrionException(OrionException.Kind.TRANSPORT,
                                "Host board: " + l.substring(3).trim());
                    }
                } else if (c != '\r') {
                    lineBuf.append(c);
                }
            }
        }
        throw new OrionException(OrionException.Kind.TIMEOUT,
                "Host board did not answer \"" + line + "\"");
    }
}
