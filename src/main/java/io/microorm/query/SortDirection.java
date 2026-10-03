package io.microorm.query;

/** Sort order of an {@code ORDER BY} item. */
public enum SortDirection {

    /** Ascending order. */
    ASC("ASC"),
    /** Descending order. */
    DESC("DESC");

    private final String sql;

    SortDirection(String sql) {
        this.sql = sql;
    }

    /** @return the SQL text of this direction */
    public String sql() {
        return sql;
    }
}
