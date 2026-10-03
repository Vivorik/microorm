package io.microorm.query;

/** How a condition is combined with the ones before it. */
public enum LogicalOperator {

    /** {@code AND} */
    AND("AND"),
    /** {@code OR} */
    OR("OR");

    private final String sql;

    LogicalOperator(String sql) {
        this.sql = sql;
    }

    /** @return the SQL text of this operator */
    public String sql() {
        return sql;
    }
}
