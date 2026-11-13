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
    NO_BOARD(0x10, "no board at the sensor / stop / loaded"),
    BOARD_PRESENT(0x11, "a board is in the way (sensor, clamp or loaded)"),
    CLAMPED(0x12, "the clamp is engaged"),
    BUSY(0x13, "another motion is running"),
    TOO_LONG(0x14, "the board is longer than the sensor-to-pin distance"),
    WIDTH_NOT_HOMED(0x15, "the width axis has no known position"),
    SENSOR(0x16, "the entry sensor looks stuck or never changed"),
    WIDTH_LIMIT(0x17, "width outside the allowed range"),
    WATCHDOG(0x18, "the host went silent during a move"),
    ENTRY_BLOCKED(0x19, "a board waiting at the entry blocks this"),
    NEXT_OVERRUN(0x1A, "the waiting board would be pushed past the stop"),
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
