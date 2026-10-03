package io.microorm.exception;

/** Thrown when entity metadata cannot be derived, e.g. a missing {@code @Id} or an unsupported field type. */
public final class MappingException extends RuntimeException implements MicroOrmException {

    private static final long serialVersionUID = 1L;

    public MappingException(String message) {
        super(message);
    }

    public MappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
