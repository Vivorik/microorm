package io.microorm.pool;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * {@link DataSource} backed by a {@link ConnectionPool}.
 *
 * <p>This is the bridge that lets MicroORM stay unaware of pooling: the session layer only ever asks
 * a {@code DataSource} for connections, so an application can plug in its own pool later.
 *
 * <p>{@link #getConnection(String, String)} is rejected on purpose: credentials belong to the
 * physical data source, so honouring them here would mean either ignoring them or opening
 * unpooled connections.
 */
public final class PooledDataSource implements DataSource, AutoCloseable {

    private final ConnectionPool pool;

    public PooledDataSource(ConnectionPool pool) {
        this.pool = pool;
    }

    /** @return the underlying pool, exposed for metrics */
    public ConnectionPool pool() {
        return pool;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return pool.borrow();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "MicroORM pools a single credential set; configure them on the physical DataSource");
    }

    /** Closes every physical connection owned by the pool. */
    @Override
    public void close() {
        pool.close();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        throw new SQLFeatureNotSupportedException("log writer is not supported");
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        throw new SQLFeatureNotSupportedException("log writer is not supported");
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw new SQLFeatureNotSupportedException("login timeout is configured on the physical DataSource");
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        throw new SQLFeatureNotSupportedException("login timeout is configured on the physical DataSource");
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("parent logger is not supported");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}