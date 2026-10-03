package io.microorm.exception;

/** Thrown when the database rejects an operation: constraint violation, connection loss, bad SQL. */
public final class PersistenceException extends RuntimeException implements MicroOrmException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
