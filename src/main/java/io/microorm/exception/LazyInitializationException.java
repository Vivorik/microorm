package io.microorm.exception;

/**
 * Thrown when a lazy proxy is dereferenced after its session was closed, which is exactly the
 * {@code LazyInitializationException} users of Hibernate know too well.
 */
public final class LazyInitializationException extends RuntimeException implements MicroOrmException {

    private static final long serialVersionUID = 1L;

    public LazyInitializationException(String message) {
        super(message);
    }
}
