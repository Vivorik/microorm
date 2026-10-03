package io.microorm.pool;

import java.sql.Connection;
import java.time.Instant;

/**
 * A physical JDBC connection together with the timestamps the pool needs for its eviction policy.
 *
 * @param connection the connection as returned by the driver
 * @param createdAt  when the pool opened it
 * @param lastUsedAt when the pool last handed it out or took it back
 */
record PhysicalConnection(Connection connection, Instant createdAt, Instant lastUsedAt) {

    /** @param now current time */
    PhysicalConnection touchedAt(Instant now) {
        return new PhysicalConnection(connection, createdAt, now);
    }
}
