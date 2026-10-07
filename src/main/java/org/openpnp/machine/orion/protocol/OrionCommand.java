package org.openpnp.machine.orion.protocol;

/** Command / reply codes, see Feeders/Feeder board/SW/PROTOCOL.md in the orionPnP repo. */
public final class OrionCommand {
    private OrionCommand() {}

    public static final int PING = 0x01;
    public static final int DISCOVER = 0x10;
    public static final int ASSIGN_ADDR = 0x11;
    public static final int GET_COMPONENT = 0x20;
    public static final int SET_COMPONENT = 0x21;
    public static final int SET_FEED_CONFIG = 0x22;
    public static final int RESET_CONFIG = 0x23;
    public static final int ZERO_HERE = 0x24;
    public static final int SET_PITCH_MM = 0x25;
    public static final int FEED_NEXT = 0x26;
    public static final int SET_EXT_LED = 0x27;
    public static final int SET_INVERT_DIR = 0x28;
    public static final int GET_HW_INFO = 0x29;
    public static final int SET_HW_INFO = 0x2A;
    public static final int GET_STATUS = 0x30;
    public static final int STOP = 0x31;
    public static final int IDENTIFY = 0x32;
    public static final int GET_SERIAL = 0x33;
    public static final int PEEL = 0x34;
    public static final int SET_PEEL_TIME = 0x35;
    public static final int GET_PEEL_TIME = 0x36;
    public static final int JOG = 0x37;
    public static final int I2C_SCAN = 0x38;
    public static final int SET_SERIAL = 0x39;
    public static final int SET_LED_BRIGHTNESS = 0x3A;
    public static final int SET_PEEL_RATE = 0x3B;
    public static final int GET_PEEL_RATE = 0x3C;
    public static final int FEED_BACK = 0x3D;

    public static final int PONG = 0x81;
    public static final int ACK = 0x82;
    public static final int NACK = 0x83;
    public static final int DISCOVER_HERE = 0x90;
    public static final int COMPONENT_INFO = 0xA0;
    public static final int HW_INFO = 0xA1;
    public static final int STATUS_INFO = 0xA2;
    public static final int SERIAL_INFO = 0xA3;
    public static final int PEEL_TIME_INFO = 0xA4;
    public static final int I2C_SCAN_INFO = 0xA5;
    public static final int PEEL_RATE_INFO = 0xA6;

    public static final int ADDR_BROADCAST = 0;
    public static final int ADDR_MAX = 247;

    public static String name(int cmd) {
        switch (cmd) {
            case PING: return "PING";
            case DISCOVER: return "DISCOVER";
            case ASSIGN_ADDR: return "ASSIGN_ADDR";
            case GET_COMPONENT: return "GET_COMPONENT";
            case SET_COMPONENT: return "SET_COMPONENT";
            case SET_FEED_CONFIG: return "SET_FEED_CONFIG";
            case RESET_CONFIG: return "RESET_CONFIG";
            case ZERO_HERE: return "ZERO_HERE";
            case SET_PITCH_MM: return "SET_PITCH_MM";
            case FEED_NEXT: return "FEED_NEXT";
            case SET_EXT_LED: return "SET_EXT_LED";
            case SET_INVERT_DIR: return "SET_INVERT_DIR";
            case GET_HW_INFO: return "GET_HW_INFO";
            case SET_HW_INFO: return "SET_HW_INFO";
            case GET_STATUS: return "GET_STATUS";
            case STOP: return "STOP";
            case IDENTIFY: return "IDENTIFY";
            case GET_SERIAL: return "GET_SERIAL";
            case PEEL: return "PEEL";
            case SET_PEEL_TIME: return "SET_PEEL_TIME";
            case GET_PEEL_TIME: return "GET_PEEL_TIME";
            case JOG: return "JOG";
            case I2C_SCAN: return "I2C_SCAN";
            case SET_SERIAL: return "SET_SERIAL";
            case SET_LED_BRIGHTNESS: return "SET_LED_BRIGHTNESS";
            case SET_PEEL_RATE: return "SET_PEEL_RATE";
            case GET_PEEL_RATE: return "GET_PEEL_RATE";
            case FEED_BACK: return "FEED_BACK";
            case PONG: return "PONG";
            case ACK: return "ACK";
            case NACK: return "NACK";
            case DISCOVER_HERE: return "DISCOVER_HERE";
            case COMPONENT_INFO: return "COMPONENT_INFO";
            case HW_INFO: return "HW_INFO";
            case STATUS_INFO: return "STATUS_INFO";
            case SERIAL_INFO: return "SERIAL_INFO";
            case PEEL_TIME_INFO: return "PEEL_TIME_INFO";
            case I2C_SCAN_INFO: return "I2C_SCAN_INFO";
            case PEEL_RATE_INFO: return "PEEL_RATE_INFO";
            default: return String.format("0x%02X", cmd);
        }
    }
}
