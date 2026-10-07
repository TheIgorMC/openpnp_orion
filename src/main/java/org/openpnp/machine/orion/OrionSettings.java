package org.openpnp.machine.orion;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Root;

/** Machine-wide Orion bus settings, stored as a machine property (machine.xml). */
@Root
public class OrionSettings {
    public enum Mode {
        SIMULATED("Simulated (no hardware)"),
        USB_RS485("USB-RS485 adapter (1 rail)"),
        HOST_BOARD("Orion host board (2 rails)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Attribute(required = false)
    private Mode mode = Mode.USB_RS485;

    @Attribute(required = false)
    private String adapterPort = "";

    @Attribute(required = false)
    private int adapterBaud = 9600;

    @Attribute(required = false)
    private String hostPort = "";

    @Attribute(required = false)
    private int hostBaud = 115200;

    @Attribute(required = false)
    private int maxAddress = 40;

    @Attribute(required = false)
    private int retries = 2;

    @Attribute(required = false)
    private boolean connectOnEnable = true;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getAdapterPort() {
        return adapterPort;
    }

    public void setAdapterPort(String adapterPort) {
        this.adapterPort = adapterPort;
    }

    public int getAdapterBaud() {
        return adapterBaud;
    }

    public void setAdapterBaud(int adapterBaud) {
        this.adapterBaud = adapterBaud;
    }

    public String getHostPort() {
        return hostPort;
    }

    public void setHostPort(String hostPort) {
        this.hostPort = hostPort;
    }

    public int getHostBaud() {
        return hostBaud;
    }

    public void setHostBaud(int hostBaud) {
        this.hostBaud = hostBaud;
    }

    public int getMaxAddress() {
        return maxAddress;
    }

    public void setMaxAddress(int maxAddress) {
        this.maxAddress = maxAddress;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public boolean isConnectOnEnable() {
        return connectOnEnable;
    }

    public void setConnectOnEnable(boolean connectOnEnable) {
        this.connectOnEnable = connectOnEnable;
    }
}
