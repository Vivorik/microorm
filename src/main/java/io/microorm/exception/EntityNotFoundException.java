package io.microorm.exception;

/** Thrown by {@code Session.findOrThrow} when no row matches the identifier. */
public final class EntityNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Class<?> entityType;
    private final transient Object entityId;

    public EntityNotFoundException(Class<?> entityType, Object entityId) {
        super(entityType.getSimpleName() + " with id " + entityId + " was not found");
        this.entityType = entityType;
        this.entityId = entityId;
    }

    /** @return entity class that was looked up */
    public Class<?> entityType() {
        return entityType;
    }

    /** @return identifier that was looked up */
    public Object entityId() {
        return entityId;
    }
}
