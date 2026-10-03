package io.microorm.sql;

import io.microorm.metadata.EntityMetadata;
import java.util.List;

/**
 * {@code SELECT columns FROM table ...} that produces entity rows.
 *
 * @param sql        SQL text
 * @param parameters bind parameters in placeholder order
 * @param entity     metadata describing how to map a row
 */
public record SelectStatement(String sql, List<Bind> parameters, EntityMetadata entity)
        implements SqlStatement {

    public SelectStatement {
        parameters = List.copyOf(parameters);
    }
}
