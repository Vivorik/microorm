package io.microorm.query;

/**
 * SQL comparison operators the query builder accepts.
 *
 * <p>The operator is rendered from this enum, never from user input, so it cannot be used to smuggle
 * SQL into a statement. Every operator maps to exactly one piece of SQL text.
 */
public enum ComparisonOperator {

    /** {@code =} */
    EQUAL("="),
    /** {@code <>} */
    NOT_EQUAL("<>"),
    /** {@code >} */
    GREATER(">"),
    /** {@code >=} */
    GREATER_OR_EQUAL(">="),
    /** {@code <} */
    LESS("<"),
    /** {@code <=} */
    LESS_OR_EQUAL("<="),
    /** {@code LIKE} */
    LIKE("LIKE"),
    /** {@code NOT LIKE} */
    NOT_LIKE("NOT LIKE"),
    /** {@code IS NULL} */
    IS_NULL("IS NULL"),
    /** {@code IS NOT NULL} */
    IS_NOT_NULL("IS NOT NULL"),
    /** {@code IN (...)} */
    IN("IN"),
    /** {@code NOT IN (...)} */
    NOT_IN("NOT IN");

    private final String sql;

    ComparisonOperator(String sql) {
        this.sql = sql;
    }

    /** @return the SQL text of this operator */
    public String sql() {
        return sql;
    }

    /** @return {@code true} when the operator takes no value */
    public boolean isUnary() {
        return this == IS_NULL || this == IS_NOT_NULL;
    }

    /** @return {@code true} when the operator accepts a list of values */
    public boolean isMultiValued() {
        return this == IN || this == NOT_IN;
    }
}
