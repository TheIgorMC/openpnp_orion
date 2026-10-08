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

    private static volatile String libraryProblem;
    private static boolean nativePrepared;

    /**
     * jSerialComm normally unpacks its native library into the temp folder and tries several CPU
     * architectures in turn (ARM first on Windows). When that unpacking fails (locked or stale file,
     * antivirus) it ends up trying an ARM DLL on an x64 JVM. Here the matching library is copied once
     * from the jar to a folder of our own and jSerialComm is pointed at it with
     * {@code jSerialComm.library.path}, which skips the whole dance. Must run before SerialPort is first
     * used; harmless otherwise, and never fatal.
     */
    public static synchronized void prepareNativeLibrary() {
        if (nativePrepared || !System.getProperty("jSerialComm.library.path", "").isEmpty()) {
            return;
        }
        nativePrepared = true;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            String dir;
            String file;
            if (arch.equals("amd64") || arch.equals("x86_64")) {
                dir = "x86_64";
            } else if (arch.equals("aarch64") || arch.equals("arm64")) {
                dir = "aarch64";
            } else {
                return;
            }
            if (os.contains("win")) {
                dir = "Windows/" + dir;
                file = "jSerialComm.dll";
            } else if (os.contains("linux")) {
                dir = "Linux/" + dir;
                file = "libjSerialComm.so";
            } else {
                return;
            }
            java.io.File target = new java.io.File(System.getProperty("user.home"),
                    ".openpnp2" + java.io.File.separator + "orion-native" + java.io.File.separator
                            + dir.replace('/', '_'));
            java.io.File lib = new java.io.File(target, file);
            try (java.io.InputStream in = JSerialCommChannel.class.getResourceAsStream("/" + dir + "/" + file)) {
                if (in == null) {
                    return;
                }
                byte[] bytes = in.readAllBytes();
                if (!lib.exists() || lib.length() != bytes.length) {
                    target.mkdirs();
                    java.io.File tmp = new java.io.File(target, file + ".part");
                    java.nio.file.Files.write(tmp.toPath(), bytes);
                    java.nio.file.Files.move(tmp.toPath(), lib.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.setProperty("jSerialComm.library.path", target.getAbsolutePath() + java.io.File.separator);
        } catch (Throwable t) {
            // Fall back to jSerialComm's own extraction.
            org.pmw.tinylog.Logger.warn("Orion: could not prepare the serial library: " + t);
        }
    }

    /** Human readable reason why the native serial library could not be loaded, or null if it works. */
    public static String getLibraryProblem() {
        return libraryProblem;
    }

    /**
     * Wraps a failed native library load into a message with what is needed to diagnose it. The
     * jSerialComm native library is extracted to the temp folder on first use; a stale or locked file
     * there (or a wrong architecture) makes it fail with UnsatisfiedLinkError.
     */
    private static String describeLibraryFailure(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String detail = String.valueOf(root.getMessage()).replaceAll("\\s*\\n\\s*", " | ");
        return String.format("The serial port library (jSerialComm) could not be loaded: %s. "
                + "os.arch=%s, java=%s %s, jSerialComm.library.path=%s. Fix: close every OpenPnP/Java "
                + "process, delete %%TEMP%%\\jSerialComm, %%USERPROFILE%%\\.jSerialComm and "
                + "%%USERPROFILE%%\\.openpnp2\\orion-native, start again; try JDK 21. The simulated "
                + "interface does not need it.", detail, System.getProperty("os.arch"),
                System.getProperty("java.vm.name"), System.getProperty("java.version"),
                System.getProperty("jSerialComm.library.path", "(unset)"));
    }

    public static String[] listPorts() {
        prepareNativeLibrary();
        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            libraryProblem = null;
            String[] names = new String[ports.length];
            for (int i = 0; i < ports.length; i++) {
                names[i] = ports[i].getSystemPortName();
            }
            return names;
        } catch (Throwable t) { // UnsatisfiedLinkError / NoClassDefFoundError / ExceptionInInitializerError
            libraryProblem = describeLibraryFailure(t);
            return new String[0];
        }
    }

    @Override
    public void open() throws OrionException {
        if (isOpen()) {
            return;
        }
        if (portName == null || portName.isEmpty()) {
            throw new OrionException(OrionException.Kind.TRANSPORT, "No serial port configured");
        }
        SerialPort p;
        prepareNativeLibrary();
        try {
            p = SerialPort.getCommPort(portName);
        } catch (Throwable t) {
            libraryProblem = describeLibraryFailure(t);
            throw new OrionException(OrionException.Kind.TRANSPORT, libraryProblem);
        }
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
