package io.microorm.exception;

/**
 * Thrown when a mutating operation is attempted without an active transaction.
 *
 * <p>MicroORM deliberately does not silently fall back to auto-commit: a half-written aggregate
 * is much harder to debug than an immediate error.
 */
public final class TransactionRequiredException extends RuntimeException implements MicroOrmException {

    private static final long serialVersionUID = 1L;

    public TransactionRequiredException(String message) {
        super(message);
    }
}
