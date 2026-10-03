package io.microorm.exception;

/**
 * Thrown when an UPDATE or DELETE affects zero rows because the row was modified by another
 * transaction, i.e. the value of the {@code @Version} column no longer matches.
 */
public final class OptimisticLockException extends RuntimeException implements MicroOrmException {

    private static final long serialVersionUID = 1L;

    private final transient Object entityId;

    public OptimisticLockException(String message, Object entityId) {
        super(message);
        this.entityId = entityId;
    }

    /** @return identifier of the entity that could not be updated */
    public Object entityId() {
        return entityId;
    }
}
