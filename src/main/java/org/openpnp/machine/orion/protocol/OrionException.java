package org.openpnp.machine.orion.protocol;

public class OrionException extends Exception {
    public enum Kind { TIMEOUT, NACK, UNEXPECTED_REPLY, TRANSPORT, CONFLICT, NOT_FOUND }

    public final Kind kind;
    public final OrionError error;

    public OrionException(Kind kind, String message) {
        this(kind, OrionError.NONE, message, null);
    }

    public OrionException(Kind kind, OrionError error, String message) {
        this(kind, error, message, null);
    }

    public OrionException(Kind kind, OrionError error, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.error = error;
    }
}
