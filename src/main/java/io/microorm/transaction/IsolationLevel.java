package io.microorm.transaction;

import java.sql.Connection;

/** Transaction isolation levels, mapped onto the JDBC constants. */
public enum IsolationLevel {

    /** Dirty reads become visible. Useful only as an optimisation inside a single trust domain. */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

    /** PostgreSQL default: each statement sees committed data. */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

    /** The transaction keeps its own snapshot, so it can be descheduled safely. */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

    /** Full serialisability, with the possibility of a serialization failure on commit. */
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int jdbcLevel;

    IsolationLevel(int jdbcLevel) {
        this.jdbcLevel = jdbcLevel;
    }

    /** @return the constant from {@link Connection} */
    public int jdbcLevel() {
        return jdbcLevel;
    }

    /**
     * @param jdbcLevel constant from {@link Connection}
     * @return matching level
     * @throws IllegalArgumentException for constants MicroORM does not model
     */
    public static IsolationLevel fromJdbc(int jdbcLevel) {
        for (IsolationLevel level : values()) {
            if (level.jdbcLevel == jdbcLevel) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unsupported isolation level: " + jdbcLevel);
    }
}
