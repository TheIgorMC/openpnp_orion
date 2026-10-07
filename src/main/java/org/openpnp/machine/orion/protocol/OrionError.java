package org.openpnp.machine.orion.protocol;

/** Error codes carried in a NACK payload / the status lastMoveErr field. */
public enum OrionError {
    NONE(0x00, "success"),
    FAULT(0x01, "motor driver fault during the move"),
    MAGNET_LOST(0x02, "encoder lost the magnet during the move"),
    STALL(0x03, "no encoder motion (stall / jam)"),
    TIMEOUT(0x04, "move timed out before reaching the target"),
    BAD_PARAM(0x05, "bad or out-of-range parameter"),
    NOT_READY(0x06, "not calibrated yet (pitch / zero / peel time)"),
    I2C(0x07, "EEPROM did not answer or write failed"),
    LOCKED(0x08, "factory serial present, refused"),
    UNKNOWN(0xFF, "unknown error");

    public final int code;
    public final String description;

    OrionError(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static OrionError fromCode(int code) {
        for (OrionError e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return UNKNOWN;
    }
}
