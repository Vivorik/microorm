package io.microorm.transaction;

import java.sql.Connection;

/**
 * Callback that disposes of a connection which must not be reused.
 *
 * <p>Exists so that {@link TransactionManager} can flag a broken connection without depending on the
 * pool implementation: the pool plugs its own {@code invalidate} method in.
 */
@FunctionalInterface
public interface ConnectionInvalidator {

    /**
     * @param connection the connection that must not be reused
     * @param reason     human readable cause, used for logging
     */
    void invalidate(Connection connection, String reason);
}
