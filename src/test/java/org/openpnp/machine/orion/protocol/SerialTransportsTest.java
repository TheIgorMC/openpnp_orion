package org.openpnp.machine.orion.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

public class SerialTransportsTest {
    /** Scripted channel: each write triggers queued response bytes. */
    static class FakeChannel implements SerialChannel {
        boolean open;
        final Deque<byte[]> responses = new ArrayDeque<>();
        final Deque<Byte> rx = new ArrayDeque<>();
        final List<String> writes = new ArrayList<>();
        boolean echo;

        public void open() { open = true; }
        public void close() { open = false; }
        public boolean isOpen() { return open; }
        public void write(byte[] d) {
            writes.add(new String(d, StandardCharsets.ISO_8859_1));
            if (echo) {
                for (byte b : d) {
                    rx.add(b);
                }
            }
            byte[] r = responses.poll();
            if (r != null) {
                for (byte b : r) {
                    rx.add(b);
                }
            }
        }
        public int read(byte[] buf, int timeoutMs) {
            int n = 0;
            while (n < buf.length && !rx.isEmpty()) {
                buf[n++] = rx.poll();
            }
            if (n == 0) {
                try {
                    Thread.sleep(Math.min(timeoutMs, 5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return n;
        }
        public void flushInput() { rx.clear(); }
        public String describe() { return "fake"; }
    }

    @Test
    public void adapterReturnsReplyAndStripsEcho() throws Exception {
        FakeChannel ch = new FakeChannel();
        ch.echo = true;
        ch.responses.add(new OrionFrame(3, OrionCommand.PONG).encode());
        SerialRs485Transport t = new SerialRs485Transport(ch);
        t.open();
        List<OrionFrame> r = t.exchange(0, new OrionFrame(3, OrionCommand.PING), 200, 0);
        assertEquals(1, r.size());
        assertEquals(OrionCommand.PONG, r.get(0).command);
    }

    @Test
    public void adapterTimeoutGivesEmptyList() throws Exception {
        SerialRs485Transport t = new SerialRs485Transport(new FakeChannel());
        t.open();
        assertTrue(t.exchange(0, new OrionFrame(3, OrionCommand.PING), 30, 0).isEmpty());
    }

    @Test
    public void hostBoardParsesVersionAndRx() throws Exception {
        FakeChannel ch = new FakeChannel();
        ch.responses.add("OK orion-host v0.1 rails=2\n".getBytes());
        HostBoardTransport t = new HostBoardTransport(ch);
        t.open();
        assertEquals(2, t.getRailCount());
        OrionFrame pong = new OrionFrame(4, OrionCommand.PONG);
        ch.responses.add(("RX " + pong.toHex() + "\r\nOK\r\n").getBytes());
        List<OrionFrame> r = t.exchange(1, new OrionFrame(4, OrionCommand.PING), 100, 0);
        assertEquals(1, r.size());
        assertTrue(ch.writes.get(1).startsWith("RS485 1 AA040100"));
    }

    @Test
    public void hostBoardErrorBecomesException() throws Exception {
        FakeChannel ch = new FakeChannel();
        ch.responses.add("OK orion-host v0.1 rails=2\n".getBytes());
        HostBoardTransport t = new HostBoardTransport(ch);
        t.open();
        ch.responses.add("ERR rail 1 power off\n".getBytes());
        try {
            t.exchange(1, new OrionFrame(4, OrionCommand.PING), 50, 0);
            fail();
        } catch (OrionException e) {
            assertTrue(e.getMessage().contains("power off"));
        }
    }
}
