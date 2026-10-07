package org.openpnp.machine.orion.protocol;

import com.fazecast.jSerialComm.SerialPort;

/** {@link SerialChannel} on top of jSerialComm (same library the GcodeDriver uses). */
public class JSerialCommChannel implements SerialChannel {
    private final String portName;
    private final int baud;
    private SerialPort port;

    public JSerialCommChannel(String portName, int baud) {
        this.portName = portName;
        this.baud = baud;
    }

    public static String[] listPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] names = new String[ports.length];
        for (int i = 0; i < ports.length; i++) {
            names[i] = ports[i].getSystemPortName();
        }
        return names;
    }

    @Override
    public void open() throws OrionException {
        if (isOpen()) {
            return;
        }
        if (portName == null || portName.isEmpty()) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "No serial port configured");
        }
        SerialPort p = SerialPort.getCommPort(portName);
        p.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 20, 0);
        if (!p.openPort()) {
            throw new OrionException(OrionException.Kind.TRANSPORT,
                    "Cannot open " + portName + " (in use by another program?)");
        }
        port = p;
    }

    @Override
    public void close() {
        if (port != null) {
            port.closePort();
            port = null;
        }
    }

    @Override
    public boolean isOpen() {
        return port != null && port.isOpen();
    }

    @Override
    public void write(byte[] data) throws OrionException {
        if (!isOpen()) {
            throw new OrionException(OrionException.Kind.TRANSPORT, portName + " is not open");
        }
        int n = port.writeBytes(data, data.length);
        if (n != data.length) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "Short write on " + portName);
        }
    }

    @Override
    public int read(byte[] buffer, int timeoutMs) throws OrionException {
        if (!isOpen()) {
            throw new OrionException(OrionException.Kind.TRANSPORT, portName + " is not open");
        }
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, Math.max(1, timeoutMs), 0);
        int n = port.readBytes(buffer, buffer.length);
        if (n < 0) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "Read error on " + portName
                    + " (adapter unplugged?)");
        }
        return n;
    }

    @Override
    public void flushInput() {
        if (isOpen()) {
            port.flushIOBuffers();
        }
    }

    @Override
    public String describe() {
        return portName + " @ " + baud;
    }
}
