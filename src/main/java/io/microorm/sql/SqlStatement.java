package io.microorm.sql;

import java.util.List;

/**
 * A SQL statement with its ordered bind parameters, ready to be prepared.
 *
 * <p>Statements are immutable value objects: generating them is a pure function of metadata and
 * values, which is what makes the SQL layer unit-testable without a database.
 */
public sealed interface SqlStatement
        permits InsertStatement, UpdateStatement, DeleteStatement, SelectStatement {

    /** @return SQL text with {@code ?} placeholders */
    String sql();

    /** @return bind parameters in placeholder order */
    List<Bind> parameters();

    /** @return number of placeholders in {@link #sql()} */
    default int parameterCount() {
        return parameters().size();
    }

    /** @return SQL text followed by the bind parameters, used by debug logs */
    default String describe() {
        return sql() + " " + parameters();
    }
}
