package io.microorm.exception;

/** Thrown when the connection pool cannot hand out a connection within the configured timeout. */
public final class ConnectionPoolException extends RuntimeException implements MicroOrmException {

    private static final long serialVersionUID = 1L;

    public ConnectionPoolException(String message) {
        super(message);
    }

    public ConnectionPoolException(String message, Throwable cause) {
        super(message, cause);
    }
}
