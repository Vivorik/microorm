package io.microorm.pool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * {@link Connection} wrapper that turns {@code close()} into "return to the pool".
 *
 * <p>{@link Connection} is an interface with about fifty methods, so a hand-written delegate would be
 * fifty forwarding methods that never change. A {@link Proxy} keeps the wrapper to the handful of
 * methods whose behaviour actually differs from a plain delegation, which is the same reason every
 * production pool does it this way.
 *
 * <p>The wrapper is created with {@link #wrap}; instances always come from a dynamic proxy, so
 * {@code PooledConnection} is intentionally not instantiable.
 */
final class PooledConnection implements InvocationHandler {

    private final PhysicalConnection physical;
    private final Consumer<Connection> onClose;
    private volatile boolean logicallyClosed;
    private volatile boolean discarded;

    private PooledConnection(PhysicalConnection physical, Consumer<Connection> onClose) {
        this.physical = physical;
        this.onClose = onClose;
    }

    /**
     * Creates a pooled wrapper around a physical connection.
     *
     * @param physical the connection to wrap
     * @param onClose  callback invoked with the proxy when the caller closes the wrapper
     * @return a proxy implementing {@link Connection}
     */
    static Connection wrap(PhysicalConnection physical, Consumer<Connection> onClose) {
        PooledConnection handler = new PooledConnection(physical, onClose);
        return (Connection) Proxy.newProxyInstance(
                PooledConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }

    /**
     * Extracts the wrapper state from a pooled connection.
     *
     * @param proxy connection obtained from the pool
     * @return the handler behind the proxy
     */
    static PooledConnection of(Connection proxy) {
        if (Proxy.isProxyClass(proxy.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(proxy);
            if (handler instanceof PooledConnection pooled) {
                return pooled;
            }
        }
        throw new IllegalArgumentException("Not a pooled connection: " + proxy.getClass());
    }

    PhysicalConnection physical() {
        return physical;
    }

    Connection connection() {
        return physical.connection();
    }

    boolean logicallyClosed() {
        return logicallyClosed;
    }

    /**
     * Marks the connection as broken so that {@code close()} disposes of it instead of reusing it.
     *
     * <p>Called by the pool when a statement fails: a connection that raised a {@link SQLException}
     * may be in an aborted transaction and must not be handed to the next caller.
     *
     * @param reason logged by the pool
     */
    void discard(String reason) {
        this.discarded = true;
    }

    boolean discarded() {
        return discarded;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("close".equals(name)) {
            // NOTE: close() is the whole point of the wrapper: it is idempotent, so closing twice
            // must not return the connection to the pool twice.
            if (!logicallyClosed) {
                logicallyClosed = true;
                onClose.accept((Connection) proxy);
            }
            return null;
        }
        if ("isClosed".equals(name)) {
            return logicallyClosed || physical.connection().isClosed();
        }
        if ("toString".equals(name)) {
            return "PooledConnection[" + physical.connection() + "]";
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return proxy == args[0];
        }
        if (logicallyClosed) {
            throw new SQLException("Connection has already been returned to the pool");
        }
        try {
            return method.invoke(physical.connection(), args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}