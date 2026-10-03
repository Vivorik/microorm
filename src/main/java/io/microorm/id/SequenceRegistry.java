package io.microorm.id;

import io.microorm.exception.PersistenceException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches which sequences exist in the database.
 *
 * <p>The lookup is needed by {@link AutoIdGenerator}, and it is cached because a schema lookup per
 * INSERT would be absurd. The cache is per connection: different connections may see different
 * schemas, and a pooled connection can outlive a migration.
 */
public final class SequenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SequenceRegistry.class);

    private final java.util.Map<String, Boolean> known = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @param connection   connection to inspect
     * @param sequenceName sequence name to look for
     * @return {@code true} when the sequence exists in the current schema
     * @throws PersistenceException when the metadata cannot be read
     */
    public boolean exists(Connection connection, String sequenceName) {
        return known.computeIfAbsent(sequenceName, name -> lookup(connection, name));
    }

    /** Forgets everything, used after a schema migration. */
    public void invalidate() {
        known.clear();
    }

    private boolean lookup(Connection connection, String sequenceName) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            // NOTE: PostgreSQL reports sequences through getTables; pgjdbc has included them since
            // 9.x, which is why a plain table lookup is enough here.
            try (ResultSet tables = metaData.getTables(connection.getCatalog(), null, sequenceName,
                    new String[]{"SEQUENCE"})) {
                if (tables.next()) {
                    log.debug("Sequence {} exists", sequenceName);
                    return true;
                }
            }
            try (ResultSet any = metaData.getTables(null, null, sequenceName, null)) {
                boolean found = any.next();
                log.debug("Sequence {} {}", sequenceName, found ? "exists" : "does not exist");
                return found;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Cannot inspect database metadata for sequence "
                    + sequenceName, e);
        }
    }
}
