package io.microorm.sql;

import java.util.List;

/**
 * {@code DELETE FROM table WHERE id = ?}, optionally guarded by the optimistic locking version.
 *
 * @param sql             SQL text
 * @param parameters      bind parameters in placeholder order
 * @param versionGuarded  whether the WHERE clause contains the version column
 */
public record DeleteStatement(String sql, List<Bind> parameters, boolean versionGuarded)
        implements SqlStatement {

    public DeleteStatement {
        parameters = List.copyOf(parameters);
    }
}
