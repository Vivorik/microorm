package io.microorm.metadata;

/** Kinds of association MicroORM knows about. */
public enum AssociationType {

    /** Many rows reference one row of the target table: implemented. */
    MANY_TO_ONE,

    /**
     * One row references many rows: recognised only to fail fast.
     *
     * @see io.microorm.annotation.OneToMany
     */
    ONE_TO_MANY
}
