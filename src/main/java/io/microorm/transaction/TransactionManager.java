package io.microorm.transaction;

import io.microorm.exception.PersistenceException;
import io.microorm.exception.TransactionRequiredException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the JDBC connection of one session and the transaction running on it.
 *
 * <p>The connection is borrowed lazily on first use and returned when the session closes. Holding
 * exactly one connection per session is the same trade-off Hibernate makes: it removes any question
 * about which connection a given statement runs on, at the cost of holding a connection for the
 * whole session lifetime.
 */
public final class TransactionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

    private final DataSource dataSource;
    private final IsolationLevel defaultIsolationLevel;
    private final ConnectionInvalidator invalidator;

    private Connection connection;
    private Transaction current;
    private boolean closed;

    /**
     * @param dataSource          source of connections, typically a {@code PooledDataSource}
     * @param defaultIsolationLevel isolation used by {@link #begin()}
     * @param invalidator         callback that disposes of a broken connection instead of reusing it
     */
    public TransactionManager(
            DataSource dataSource,
            IsolationLevel defaultIsolationLevel,
            ConnectionInvalidator invalidator) {
        this.dataSource = dataSource;
        this.defaultIsolationLevel = defaultIsolationLevel;
        this.invalidator = invalidator;
    }

    /** @return isolation level used when the caller does not request another one */
    public IsolationLevel defaultIsolationLevel() {
        return defaultIsolationLevel;
    }

    /**
     * Starts a top level transaction, or a savepoint-backed nested one when one is already running.
     *
     * @return the transaction, which is also the current one
     * @throws TransactionRequiredException when the manager is already closed
     */
    public Transaction begin() {
        Transaction existing = current;
        if (existing != null && existing.isActive()) {
            log.debug("Reusing the active transaction as the outer one for a nested begin");
            existing.beginNested();
            return existing;
        }
        current = Transaction.begin(connection(), defaultIsolationLevel);
        return current;
    }

    /**
     * Starts a transaction with an explicit isolation level.
     *
     * @param isolationLevel isolation to request from the driver
     * @return the transaction, which is also the current one
     */
    public Transaction begin(IsolationLevel isolationLevel) {
        if (current != null && current.isActive()) {
            return begin();
        }
        current = Transaction.begin(connection(), isolationLevel);
        return current;
    }

    /** @return the running transaction, if any */
    public Optional<Transaction> current() {
        return Optional.ofNullable(current).filter(Transaction::isActive);
    }

    /** @return {@code true} while a transaction is active */
    public boolean hasActiveTransaction() {
        return current().isPresent();
    }

    /**
     * Fails unless a transaction is running.
     *
     * <p>MicroORM does not silently fall back to auto-commit for writes: a half-written aggregate is
     * much harder to debug than an immediate error, and the failure is cheap to fix.
     *
     * @param operation operation that needs a transaction, used in the error message
     * @return the running transaction
     * @throws TransactionRequiredException when no transaction is active
     */
    public Transaction requireActiveTransaction(String operation) {
        return current().orElseThrow(() -> new TransactionRequiredException(
                "Cannot " + operation + " without an active transaction; call beginTransaction() first"));
    }

    /**
     * Borrows the connection of this session, or returns the one already borrowed.
     *
     * @return the JDBC connection
     * @throws PersistenceException when the connection cannot be borrowed
     */
    public Connection connection() {
        if (closed) {
            throw new PersistenceException("The session is closed");
        }
        if (connection != null) {
            return connection;
        }
        try {
            connection = dataSource.getConnection();
            return connection;
        } catch (SQLException e) {
            throw new PersistenceException("Cannot borrow a connection from the data source", e);
        }
    }

    /**
     * Rolls back a running transaction and returns the connection to the pool.
     *
     * <p>An unfinished transaction is always rolled back: the alternative, committing half of the
     * work on close, is exactly the behaviour that makes session handling unreproducible.
     */
    @Override
    public void close() {
        closed = true;
        Transaction transaction = current;
        current = null;
        if (transaction != null && transaction.isActive()) {
            try {
                transaction.rollback();
            } catch (RuntimeException e) {
                log.warn("Failed to roll back while closing the session", e);
            }
        }
        releaseConnection();
    }

    /**
     * Marks the connection of this session as broken.
     *
     * <p>A connection that raised a {@link SQLException} may sit in an aborted transaction, so it
     * must not go back into the pool. The actual disposal is delegated to the {@link
     * ConnectionInvalidator}, which keeps this package independent of the pool implementation.
     *
     * @param reason human readable cause, used for logging
     */
    public void markConnectionBroken(String reason) {
        log.warn("Marking the connection of this session as broken: {}", reason);
        if (connection != null) {
            invalidator.invalidate(connection, reason);
        }
    }

    private void releaseConnection() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to return the connection of this session", e);
        } finally {
            connection = null;
        }
    }
}