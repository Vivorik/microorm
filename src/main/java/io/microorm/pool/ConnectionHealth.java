package io.microorm.pool;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a pooled connection may be handed out again.
 *
 * <p>Three independent questions, deliberately kept apart because they fail for different reasons:
 *
 * <ul>
 *   <li>is the connection <em>old</em>? - a policy question, answered by the clock;</li>
 *   <li>is it still <em>open</em>? - a driver question;</li>
 *   <li>is it still <em>usable</em>? - answered by executing the health check.</li>
 * </ul>
 *
 * <p>Splitting this out keeps {@link ConnectionPool} about bookkeeping - who has what, and who waits -
 * and this class about talking to the driver.
 */
final class ConnectionHealth {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHealth.class);

    private final PoolConfig config;

    ConnectionHealth(PoolConfig config) {
        this.config = config;
    }

    /**
     * @param physical the pooled connection
     * @return {@code true} when the connection outlived {@code maxLifetime} or sat unused for
     *         {@code idleTimeout}
     */
    boolean isExpired(PhysicalConnection physical) {
        Instant now = Instant.now(config.clock());
        return physical.createdAt().plus(config.maxLifetime()).isBefore(now)
                || physical.lastUsedAt().plus(config.idleTimeout()).isBefore(now);
    }

    /**
     * @param physical the pooled connection
     * @return {@code true} when the driver still considers the connection open
     */
    boolean isOpen(PhysicalConnection physical) {
        try {
            return !physical.connection().isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Runs the health check.
     *
     * @param physical the pooled connection
     * @return {@code true} when the connection answered the validation query
     */
    boolean isAlive(PhysicalConnection physical) {
        if (!isOpen(physical)) {
            return false;
        }
        Connection connection = physical.connection();
        try (Statement statement = connection.createStatement()) {
            return statement.execute(config.validationQuery());
        } catch (SQLException e) {
            log.debug("Validation query failed, discarding the connection", e);
            return false;
        }
    }

    /**
     * Preparses a connection for its next borrower.
     *
     * <p>Auto-commit is restored because a connection must not go back into the pool with an open
     * transaction: the next borrower would inherit statements it never wrote.
     *
     * @param physical the pooled connection
     * @return {@code true} when the connection can be reused
     */
    boolean prepareForReuse(PhysicalConnection physical) {
        try {
            if (!physical.connection().isClosed()) {
                physical.connection().setAutoCommit(true);
                return !config.validateOnReturn() || isAlive(physical);
            }
            return false;
        } catch (SQLException e) {
            log.debug("Connection cannot be returned to the pool", e);
            return false;
        }
    }
}