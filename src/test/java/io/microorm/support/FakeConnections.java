package io.microorm.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A spy for a single JDBC connection, used by the pool and transaction tests.
 *
 * <p>It answers the handful of methods those components are allowed to call and records what happened.
 * Anything else throws {@link UnsupportedOperationException}, which turns "the pool called something
 * unexpected" into a failing test rather than a silently accepted behaviour. For tests that need
 * scripted result sets there is {@link FakeJdbc}, which models a whole database instead of one
 * connection.
 */
public final class FakeConnections {

    private FakeConnections() {
    }

    /** Observable state of one fake physical connection. */
    public static final class State {

        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger validationCount = new AtomicInteger();
        private final AtomicInteger autoCommitResets = new AtomicInteger();
        private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile boolean broken;
        private volatile boolean closed;
        private volatile boolean serverSideClosed;
        private volatile int isolationLevel = Connection.TRANSACTION_READ_COMMITTED;
        private volatile boolean autoCommit = true;

        /** @return how often {@link Connection#close()} was called on this connection */
        public int closeCount() {
            return closeCount.get();
        }

        /** @return how often the validation query was executed */
        public int validationCount() {
            return validationCount.get();
        }

        /** @return how often the pool reset auto-commit, i.e. how often the connection was reused */
        public int autoCommitResets() {
            return autoCommitResets.get();
        }

        /**
         * @return ordered log of the transaction relevant JDBC calls, used to assert what the pool
         *         and the transaction manager actually did
         */
        public List<String> events() {
            return List.copyOf(events);
        }

        /** @return isolation level last requested through {@code setTransactionIsolation} */
        public int isolationLevel() {
            return isolationLevel;
        }

        /** @return whether auto-commit was switched off */
        public boolean autoCommit() {
            return autoCommit;
        }

        /** Simulates a driver that refuses the requested isolation level. */
        public void rejectIsolationLevel() {
            this.broken = true;
        }

        /** Simulates a connection whose server side went away. */
        public void breakConnection() {
            this.broken = true;
        }

        /** Simulates a driver that closed the connection behind the pool's back. */
        public void closeServerSide() {
            this.serverSideClosed = true;
        }

        public boolean isClosed() {
            return closed || serverSideClosed;
        }
    }

    /**
     * Creates a fake connection.
     *
     * @param state observable state to record calls into
     * @return a proxy implementing {@link Connection}
     */
    public static Connection create(State state) {
        return (Connection) Proxy.newProxyInstance(
                FakeConnections.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new Handler(state));
    }

    /**
     * Creates a {@link javax.sql.DataSource} that hands out fresh fake connections.
     *
     * @param created receives the number of physical connections requested so far
     * @param states  receives every created connection state, in creation order
     * @return a proxy implementing {@code DataSource}
     */
    public static javax.sql.DataSource dataSource(AtomicInteger created, java.util.List<State> states) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getConnection" -> {
                created.incrementAndGet();
                State state = new State();
                states.add(state);
                yield create(state);
            }
            case "getLogWriter", "getLoginTimeout", "getParentLogger" -> null;
            case "toString" -> "FakeDataSource";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("DataSource." + method.getName());
        };
        return (javax.sql.DataSource) Proxy.newProxyInstance(
                FakeConnections.class.getClassLoader(),
                new Class<?>[]{javax.sql.DataSource.class},
                handler);
    }

    private static final class Handler implements InvocationHandler {

        private final State state;

        private Handler(State state) {
            this.state = state;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "close" -> {
                    state.closeCount.incrementAndGet();
                    state.closed = true;
                    yield null;
                }
                case "isClosed" -> state.isClosed();
                case "setAutoCommit" -> {
                    state.autoCommitResets.incrementAndGet();
                    state.autoCommit = (boolean) args[0];
                    state.events.add("setAutoCommit(" + args[0] + ")");
                    yield null;
                }
                case "getAutoCommit" -> state.autoCommit;
                case "commit" -> {
                    state.events.add("commit");
                    yield null;
                }
                case "rollback" -> {
                    if (args == null) {
                        state.events.add("rollback");
                    } else {
                        state.events.add("rollback(" + ((Savepoint) args[0]).getSavepointName() + ")");
                    }
                    yield null;
                }
                case "setSavepoint" -> {
                    String name = "unnamed";
                    if (args != null && args.length > 0) {
                        name = (String) args[0];
                    }
                    state.events.add("setSavepoint(" + name + ")");
                    yield savepoint(name);
                }
                case "releaseSavepoint" -> {
                    state.events.add("releaseSavepoint(" + ((Savepoint) args[0]).getSavepointName() + ")");
                    yield null;
                }
                case "setTransactionIsolation" -> {
                    if (state.broken) {
                        throw new SQLException("isolation level is not supported");
                    }
                    state.isolationLevel = (int) args[0];
                    state.events.add("setTransactionIsolation(" + args[0] + ")");
                    yield null;
                }
                case "getTransactionIsolation" -> state.isolationLevel;
                case "createStatement" -> statement(state);
                case "getMetaData" -> metaData();
                case "toString" -> "FakeConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Connection." + method.getName()
                        + " is not part of the pool contract");
            };
        }

        private Savepoint savepoint(String name) {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getSavepointName" -> name;
                case "toString" -> "FakeSavepoint[" + name + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Savepoint." + method.getName());
            };
            return (Savepoint) Proxy.newProxyInstance(
                    FakeConnections.class.getClassLoader(), new Class<?>[]{Savepoint.class}, handler);
        }

        private Statement statement(State state) {
            InvocationHandler statement = (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> validate(state, (String) args[0]) != null;
                case "executeQuery" -> validate(state, (String) args[0]);
                case "close" -> null;
                case "isClosed" -> false;
                case "toString" -> "FakeStatement";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Statement." + method.getName());
            };
            return (Statement) Proxy.newProxyInstance(
                    FakeConnections.class.getClassLoader(), new Class<?>[]{Statement.class}, statement);
        }

        /**
         * Runs the health check: counts the call and fails like a dead server would.
         *
         * @return a ResultSet proxy, enough for callers that only verify that validation happened
         */
        private Object validate(State state, String sql) throws SQLException {
            state.validationCount.incrementAndGet();
            if (state.broken || state.serverSideClosed) {
                throw new SQLException("connection is dead, sql=" + sql);
            }
            return Proxy.newProxyInstance(
                    FakeConnections.class.getClassLoader(),
                    new Class<?>[]{java.sql.ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> null;
                        case "next" -> Boolean.FALSE;
                        case "toString" -> "FakeResultSet";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException("ResultSet." + method.getName());
                    });
        }

        private DatabaseMetaData metaData() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getDatabaseProductName" -> "FakePostgreSQL";
                case "getDatabaseProductVersion" -> "0";
                case "toString" -> "FakeMetaData";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("DatabaseMetaData." + method.getName());
            };
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    FakeConnections.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, handler);
        }
    }
}