package io.microorm.sql;

/**
 * The handful of places where SQL is not portable.
 *
 * <p>MicroORM ships a single implementation, {@link PostgresDialect}, because a second dialect that
 * is never exercised is dead weight. The interface exists to make the shape of the abstraction
 * visible: exactly these few hooks are what makes an ORM dialect replaceable.
 */
public interface Dialect {

    /** @return human readable name used in log messages */
    String name();

    /**
     * @return {@code true} when the database supports {@code INSERT ... RETURNING}, which avoids
     *         relying on driver specific {@code getGeneratedKeys()} behaviour
     */
    default boolean supportsReturningClause() {
        return true;
    }

    /**
     * @param sequenceName validated sequence name
     * @return expression that yields the next value of the sequence
     */
    String sequenceNextValue(String sequenceName);

    /**
     * Renders the pagination clause.
     *
     * @param limit  maximum number of rows, {@code null} for no limit
     * @param offset number of rows to skip, {@code null} for no offset
     * @return clause text, empty when neither limit nor offset is set
     */
    String pagination(Integer limit, Integer offset);
}
