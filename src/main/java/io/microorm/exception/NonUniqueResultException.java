package io.microorm.exception;

/** Thrown when a query that must return at most one row returns more. */
public final class NonUniqueResultException extends RuntimeException implements MicroOrmException {

    private static final long serialVersionUID = 1L;

    public NonUniqueResultException(String message) {
        super(message);
    }
}
