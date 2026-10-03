package io.microorm.pool;

import io.microorm.exception.ConnectionPoolException;
import io.microorm.exception.PersistenceException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small connection pool, written to show what a pool actually has to do.
 *
 * <p>Responsibilities, and nothing else:
 *
 * <ul>
 *   <li>keep between {@code minSize} and {@code maxSize} physical connections;</li>
 *   <li>hand out one connection at a time to a caller and take it back on {@code close()};</li>
 *   <li>block a caller instead of opening an unbounded number of connections, and give up with
 *       {@link ConnectionPoolException} after {@code connectionTimeout};</li>
 *   <li>validate a reused connection with {@code validationQuery} before handing it out;</li>
 *   <li>retire connections that exceeded {@code idleTimeout} or {@code maxLifetime}.</li>
 * </ul>
 *
 * <p>Expiry is evaluated lazily on borrow and release rather than by a background sweeper thread:
 * a timer thread would need its own shutdown path, and a pool that is not being used has nothing to
 * sweep. The trade-off is that an idle pool keeps expired connections until the next borrow.
 *
 * <p>Instances are thread safe. The {@link ReentrantLock} plus {@link Condition} pair is the
 * standard shape: the condition is signalled on release so a waiting thread wakes up immediately
 * instead of polling.
 */
public final class ConnectionPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);

    private final DataSource dataSource;
    private final PoolConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition connectionAvailable = lock.newCondition();
    private final Deque<PhysicalConnection> idle = new ArrayDeque<>();
    private final Set<PhysicalConnection> all = ConcurrentHashMap.newKeySet();

    private int active;
    private int waiting;
    private long created;
    private long discarded;
    private volatile boolean closed;

    /**
     * Opens a pool and eagerly creates {@code minSize} connections, so a misconfigured database is
     * reported at startup rather than on the first request.
     *
     * @param dataSource source of unpooled physical connections
     * @param config     pool configuration
     * @throws PersistenceException when the initial connections cannot be established
     */
    public ConnectionPool(DataSource dataSource, PoolConfig config) {
        this.dataSource = dataSource;
        this.config = config;
        for (int i = 0; i < config.minSize(); i++) {
            PhysicalConnection connection = openPhysical();
            idle.add(connection);
        }
        log.info("Connection pool started: {}", metrics());
    }

    /**
     * Borrows a connection. The returned connection must be closed to be returned to the pool.
     *
     * @return a pooled connection
     * @throws ConnectionPoolException when no connection becomes available within the timeout
     * @throws PersistenceException    when a new physical connection cannot be opened
     */
    public Connection borrow() {
        lock.lock();
        try {
            ensureOpen();
            PhysicalConnection reusable = pollUsableIdle();
            if (reusable != null) {
                return handOut(reusable);
            }
            if (all.size() < config.maxSize()) {
                // Reserve the slot before releasing the lock, otherwise every waiting thread would
                // try to open a connection and blow past maxSize.
                return handOut(openPhysical());
            }
            return handOut(awaitAvailable());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a borrowed connection to the pool.
     *
     * @param connection connection previously obtained from {@link #borrow()}
     * @throws IllegalArgumentException when the connection does not belong to this pool
     */
    public void release(Connection connection) {
        PooledConnection wrapper = PooledConnection.of(connection);
        lock.lock();
        try {
            PhysicalConnection physical = wrapper.physical();
            if (!all.contains(physical)) {
                throw new IllegalArgumentException("Connection was not borrowed from this pool");
            }
            active--;
            if (isReusable(wrapper)) {
                idle.addLast(physical.touchedAt(Instant.now(config.clock())));
            } else {
                retire(physical);
            }
            connectionAvailable.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks a borrowed connection as broken so that releasing it disposes of the physical connection.
     *
     * @param connection connection previously obtained from {@link #borrow()}
     * @param reason     human readable cause, used for logging
     */
    public void invalidate(Connection connection, String reason) {
        PooledConnection.of(connection).discard(reason);
        log.warn("Connection discarded: {}", reason);
    }

    /** @return a snapshot of the current pool state */
    public PoolMetrics metrics() {
        lock.lock();
        try {
            return new PoolMetrics(all.size(), active, idle.size(), waiting, created, discarded);
        } finally {
            lock.unlock();
        }
    }

    /** @return the configuration this pool was created with */
    public PoolConfig config() {
        return config;
    }

    /**
     * Closes the pool and every physical connection it owns. Borrows fail afterwards.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            for (PhysicalConnection connection : all) {
                closeQuietly(connection);
            }
            all.clear();
            idle.clear();
            connectionAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        log.info("Connection pool closed: {}", metrics());
    }

    private PhysicalConnection awaitAvailable() {
        long deadline = System.nanoTime() + config.connectionTimeout().toNanos();
        waiting++;
        try {
            while (true) {
                PhysicalConnection reusable = pollUsableIdle();
                if (reusable != null) {
                    return reusable;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new ConnectionPoolException("No connection available within "
                            + config.connectionTimeout() + "; pool is saturated at maxSize="
                            + config.maxSize());
                }
                try {
                    connectionAvailable.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ConnectionPoolException("Interrupted while waiting for a connection", e);
                }
            }
        } finally {
            waiting--;
        }
    }

    /** Must be called while holding the lock. */
    private PhysicalConnection pollUsableIdle() {
        PhysicalConnection candidate = idle.peekFirst();
        while (candidate != null) {
            if (isExpired(candidate)) {
                idle.pollFirst();
                retire(candidate);
                candidate = idle.peekFirst();
            } else {
                break;
            }
        }
        candidate = idle.pollFirst();
        if (candidate == null) {
            return null;
        }
        if (!config.validateOnBorrow() || isAlive(candidate)) {
            return candidate;
        }
        // A connection that fails validation is never handed out; try the next idle one.
        retire(candidate);
        return pollUsableIdle();
    }

    private Connection handOut(PhysicalConnection physical) {
        active++;
        return PooledConnection.wrap(physical, this::release);
    }

    private boolean isReusable(PooledConnection wrapper) {
        if (wrapper.discarded() || isExpired(wrapper.physical())) {
            return false;
        }
        try {
            Connection connection = wrapper.connection();
            if (connection.isClosed()) {
                return false;
            }
            // A connection must be reusable only if the driver did not leave an open transaction
            // behind; setAutoCommit(true) both restores the default and commits a leaked one.
            connection.setAutoCommit(true);
            return !config.validateOnReturn() || isAlive(wrapper.physical());
        } catch (SQLException e) {
            log.debug("Connection cannot be returned to the pool", e);
            return false;
        }
    }

    private boolean isExpired(PhysicalConnection physical) {
        Instant now = Instant.now(config.clock());
        return physical.createdAt().plus(config.maxLifetime()).isBefore(now)
                || physical.lastUsedAt().plus(config.idleTimeout()).isBefore(now);
    }

    private boolean isAlive(PhysicalConnection physical) {
        try {
            Connection connection = physical.connection();
            if (connection.isClosed()) {
                return false;
            }
            try (Statement statement = connection.createStatement()) {
                return statement.execute(config.validationQuery());
            }
        } catch (SQLException e) {
            log.debug("Validation query failed, discarding connection", e);
            return false;
        }
    }

    private PhysicalConnection openPhysical() {
        try {
            Instant now = Instant.now(config.clock());
            PhysicalConnection physical = new PhysicalConnection(dataSource.getConnection(), now, now);
            configure(physical.connection());
            all.add(physical);
            created++;
            return physical;
        } catch (SQLException e) {
            throw new PersistenceException("Cannot open a physical connection", e);
        }
    }

    private void configure(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        DatabaseMetaData metaData = connection.getMetaData();
        log.debug("Connected to {} {}", metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());
    }

    private void retire(PhysicalConnection physical) {
        all.remove(physical);
        idle.remove(physical);
        discarded++;
        closeQuietly(physical);
    }

    private void closeQuietly(PhysicalConnection physical) {
        try {
            physical.connection().close();
        } catch (SQLException e) {
            log.warn("Failed to close a physical connection", e);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new ConnectionPoolException("Connection pool is closed");
        }
    }

    /**
     * @return number of currently open physical connections, exposed for diagnostics
     */
    public int openConnections() {
        return all.size();
    }

    /**
     * Executes a statement on a pooled connection, used by the health check of {@link PooledDataSource}.
     *
     * @param sql statement to run
     * @return whether the statement produced a result set
     * @throws SQLException when the connection cannot be borrowed or the statement fails
     */
    public boolean validate(String sql) throws SQLException {
        try (Connection connection = borrow(); Statement statement = connection.createStatement()) {
            try (ResultSet ignored = statement.executeQuery(sql)) {
                return ignored != null;
            }
        }
    }
}