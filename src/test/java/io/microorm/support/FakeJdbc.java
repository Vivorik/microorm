package io.microorm.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * A scripted in-memory stand-in for a JDBC driver.
 *
 * <p>Integration tests against a real PostgreSQL container are the authoritative ones, but they need a
 * Docker daemon. This harness makes the same behaviour testable in milliseconds, which is what keeps
 * {@code mvn test} useful on a laptop and lets the session logic be covered at all.
 *
 * <p>It is deliberately dumb: a test registers the rows a query must return and then asserts on the
 * SQL that was executed. Anything the ORM does not register comes back as an empty result, so an
 * unexpected extra query fails loudly instead of silently returning nulls.
 */
public final class FakeJdbc {

    private static final Pattern PROJECTION = Pattern.compile("^SELECT (.+?) FROM ", Pattern.DOTALL);
    private static final Pattern RETURNING = Pattern.compile("RETURNING (.+)$", Pattern.DOTALL);

    private final Map<String, FakeResultSet> results = new LinkedHashMap<>();
    private final Map<String, List<FakeResultSet>> sequenceBySql = new LinkedHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> sequencesBySql =
            new LinkedHashMap<>();
    private final Map<String, Integer> updateCounts = new LinkedHashMap<>();
    private final List<String> sequences = new ArrayList<>();
    private final List<ExecutedStatement> statements = new ArrayList<>();
    private final Map<String, Long> sequenceValues = new LinkedHashMap<>();

    private int defaultUpdateCount = 1;

    /**
     * Registers the row a SELECT has to return.
     *
     * <p>The column names are derived from the projection of the statement, so a test only has to
     * spell out the values in the order the ORM generates.
     *
     * @param sql   exact SQL text
     * @param values one row of values, positionally matching the projection
     * @return this database
     */
    @SafeVarargs
    public final FakeJdbc onQuery(String sql, Object... values) {
        results.put(sql, new FakeResultSet(columnsOf(sql), List.<Object[]>of(values.clone())));
        return this;
    }

    /**
     * Registers several rows for a SELECT.
     *
     * @param sql   exact SQL text
     * @param rows  rows of values
     * @return this database
     */
    public FakeJdbc onQueries(String sql, List<Object[]> rows) {
        results.put(sql, new FakeResultSet(columnsOf(sql), List.copyOf(rows)));
        return this;
    }

    /**
     * Registers several responses for the same statement, one per execution.
     *
     * <p>Needed when two executions of an identical statement must return different data - for example
     * when a test checks that a query is executed twice with the same SQL but different parameters. The
     * last response is repeated once the sequence is exhausted.
     *
     * @param sql       exact SQL text
     * @param responses one or more rows; each element is a response, each row is a value list
     * @return this database
     */
    @SafeVarargs
    public final FakeJdbc onQuerySequence(String sql, List<Object[]>... responses) {
        List<FakeResultSet> sequence = new java.util.ArrayList<>();
        for (List<Object[]> response : responses) {
            sequence.add(new FakeResultSet(columnsOf(sql), List.copyOf(response)));
        }
        results.put(sql, null);
        sequencesBySql.put(sql, new java.util.concurrent.atomic.AtomicInteger());
        sequenceBySql.put(sql, sequence);
        return this;
    }

    /**
     * Registers how many rows an UPDATE or DELETE affects.
     *
     * @param sql     exact SQL text
     * @param affected row count, {@code 0} to simulate an optimistic locking failure
     * @return this database
     */
    public FakeJdbc onUpdate(String sql, int affected) {
        updateCounts.put(sql, affected);
        return this;
    }

    /**
     * Registers a sequence that {@code AutoIdGenerator} should discover.
     *
     * @param sequenceName sequence name
     * @return this database
     */
    public FakeJdbc withSequence(String sequenceName) {
        sequences.add(sequenceName);
        return this;
    }

    /**
     * Sets the value the next {@code nextval} returns.
     *
     * @param sequenceName sequence name
     * @param value        next value
     * @return this database
     */
    public FakeJdbc withSequenceValue(String sequenceName, long value) {
        sequenceValues.put(sequenceName, value);
        return this;
    }

    /**
     * @param affected row count returned by any UPDATE without a specific registration
     * @return this database
     */
    public FakeJdbc withDefaultUpdateCount(int affected) {
        this.defaultUpdateCount = affected;
        return this;
    }

    /** @return every statement that was prepared or executed, in order */
    public List<ExecutedStatement> statements() {
        return List.copyOf(statements);
    }

    /** @return the SQL text of every statement that was prepared or executed, in order */
    public List<String> sql() {
        return statements.stream().map(ExecutedStatement::sql).toList();
    }

    /** @return number of statements whose SQL contains the given fragment */
    public long countStatements(String sqlFragment) {
        return statements.stream().filter(s -> s.sql().contains(sqlFragment)).count();
    }

    /**
     * @param sqlFragment fragment to look for
     * @return the first matching statement, or empty
     */
    public java.util.Optional<ExecutedStatement> statement(String sqlFragment) {
        return statements.stream().filter(s -> s.sql().contains(sqlFragment)).findFirst();
    }

    /** @return statements that modified data */
    public List<ExecutedStatement> writes() {
        return statements.stream().filter(ExecutedStatement::isWrite).toList();
    }

    /** @return a data source handing out connections bound to this database */
    public DataSource dataSource() {
        return (DataSource) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, new DataSourceHandler());
    }

    /**
     * @param sql a statement that has a registered result
     * @return a result set positioned before the first row, for mapper level tests
     */
    public java.sql.ResultSet resultSetFor(String sql) {
        return resultFor(sql).asResultSet();
    }

    /** @return a connection bound to this database */
    public Connection connection() {
        return (Connection) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new ConnectionHandler());
    }

    /**
     * Derives the column names of a statement.
     *
     * <p>Both {@code SELECT a, b FROM t} and {@code INSERT ... RETURNING id} are produced by the SQL
     * generator, so both are understood; anything else (a health check such as {@code SELECT 1}) gets a
     * single anonymous column, which is enough for a result set nobody reads by name.
     */
    private String[] columnsOf(String sql) {
        Matcher returning = RETURNING.matcher(sql);
        if (returning.find()) {
            return splitColumns(returning.group(1));
        }
        Matcher projection = PROJECTION.matcher(sql);
        if (projection.find()) {
            return splitColumns(projection.group(1));
        }
        return new String[]{"value"};
    }

    private String[] splitColumns(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).toArray(String[]::new);
    }

    private FakeResultSet resultFor(String sql) {
        List<FakeResultSet> sequence = sequenceBySql.get(sql);
        if (sequence != null) {
            int index = sequencesBySql.get(sql).getAndIncrement();
            return sequence.get(Math.min(index, sequence.size() - 1));
        }
        FakeResultSet result = results.get(sql);
        return result != null ? result : new FakeResultSet(columnsOf(sql), List.of());
    }

    private int updateCountFor(String sql) {
        return updateCounts.getOrDefault(sql, defaultUpdateCount);
    }

    /** A statement that was prepared or executed, with the values bound to it. */
    public record ExecutedStatement(String sql, List<Object> parameters) {

        /** @return {@code true} for INSERT, UPDATE and DELETE */
        public boolean isWrite() {
            String upper = sql.toUpperCase(java.util.Locale.ROOT);
            return upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE");
        }

        @Override
        public String toString() {
            return sql + " " + parameters;
        }
    }

    private final class DataSourceHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getConnection" -> connection();
                case "getLogWriter", "getLoginTimeout", "getParentLogger" -> null;
                case "toString" -> "FakeJdbc";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("DataSource." + method.getName());
            };
        }
    }

    private final class ConnectionHandler implements InvocationHandler {

        private boolean autoCommit = true;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "prepareStatement" -> preparedStatement((String) args[0]);
                case "createStatement" -> statement();
                case "getMetaData" -> metaData();
                case "getCatalog", "getSchema" -> null;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "setAutoCommit" -> {
                    autoCommit = (boolean) args[0];
                    yield null;
                }
                case "getAutoCommit" -> autoCommit;
                case "isClosed" -> false;
                case "close" -> null;
                case "commit" -> {
                    record("COMMIT");
                    yield null;
                }
                case "rollback" -> {
                    if (args == null) {
                        record("ROLLBACK");
                    } else {
                        record("ROLLBACK TO " + ((Savepoint) args[0]).getSavepointName());
                    }
                    yield null;
                }
                case "setSavepoint" -> savepoint(args == null || args.length == 0 ? "unnamed" : (String) args[0]);
                case "releaseSavepoint" -> null;
                case "setTransactionIsolation" -> null;
                case "toString" -> "FakeJdbcConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Connection." + method.getName()
                        + " is not part of the MicroORM contract");
            };
        }

        private Savepoint savepoint(String name) {
            record("SAVEPOINT " + name);
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getSavepointName" -> name;
                case "toString" -> "FakeSavepoint[" + name + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Savepoint." + method.getName());
            };
            return (Savepoint) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                    new Class<?>[]{Savepoint.class}, handler);
        }

        private DatabaseMetaData metaData() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getDatabaseProductName" -> "FakePostgreSQL";
                case "getDatabaseProductVersion" -> "16";
                case "getTables" -> tableMetadata(args.length > 2 ? (String) args[2] : null);
                case "toString" -> "FakeMetaData";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("DatabaseMetaData." + method.getName());
            };
            return (DatabaseMetaData) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, handler);
        }

        /** Answers {@code DatabaseMetaData.getTables}, which is how a sequence is discovered. */
        private ResultSet tableMetadata(String requestedName) {
            List<Object[]> rows = sequences.stream()
                    .filter(name -> requestedName == null || name.equals(requestedName))
                    .<Object[]>map(name -> new Object[]{name})
                    .toList();
            return new FakeResultSet(new String[]{"table_name"}, rows).asResultSet();
        }

        private Statement statement() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "executeQuery" -> executeQuery((String) args[0]);
                case "execute" -> {
                    executeQuery((String) args[0]).close();
                    yield Boolean.TRUE;
                }
                case "close" -> null;
                case "isClosed" -> false;
                case "toString" -> "FakeStatement";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Statement." + method.getName());
            };
            return (Statement) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                    new Class<?>[]{Statement.class}, handler);
        }

        private ResultSet executeQuery(String sql) {
            record(sql);
            Matcher nextval = Pattern.compile("^SELECT nextval\\('(.+)'\\)$").matcher(sql);
            if (nextval.matches()) {
                String sequence = nextval.group(1);
                long value = sequenceValues.getOrDefault(sequence, 1L);
                sequenceValues.put(sequence, value + 1);
                return new FakeResultSet(new String[]{"nextval"}, List.<Object[]>of(new Object[]{value}))
                        .asResultSet();
            }
            return resultFor(sql).asResultSet();
        }

        private PreparedStatement preparedStatement(String sql) {
            InvocationHandler handler = new PreparedStatementHandler(sql);
            return (PreparedStatement) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        private void record(String sql) {
            statements.add(new ExecutedStatement(sql, List.of()));
        }
    }

    /** Handles a prepared statement: collects parameters, then replays a registered result. */
    private final class PreparedStatementHandler implements InvocationHandler {

        private final String sql;
        private final Map<Integer, Object> parameters = new java.util.HashMap<>();

        private PreparedStatementHandler(String sql) {
            this.sql = sql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "setObject", "setLong", "setInt", "setShort", "setBoolean", "setString",
                     "setBigDecimal", "setDouble", "setFloat", "setDate", "setTimestamp" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "setNull" -> {
                    parameters.put((Integer) args[0], null);
                    yield null;
                }
                case "executeQuery" -> {
                    record(sql, parameters);
                    yield resultFor(sql).asResultSet();
                }
                case "executeUpdate" -> {
                    record(sql, parameters);
                    yield updateCountFor(sql);
                }
                case "execute" -> {
                    record(sql, parameters);
                    yield Boolean.TRUE;
                }
                case "close", "clearParameters" -> null;
                case "isClosed" -> false;
                case "toString" -> "FakePreparedStatement[" + sql + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("PreparedStatement." + method.getName());
            };
        }

        private void record(String sql, Map<Integer, Object> parameters) {
            List<Object> bound = new ArrayList<>();
            for (int i = 1; i <= parameters.size(); i++) {
                bound.add(parameters.get(i));
            }
            statements.add(new ExecutedStatement(sql, bound));
        }
    }

    /** Row data plus the machinery to hand out a {@link ResultSet} proxy for it. */
    private static final class FakeResultSet {

        private final String[] columns;
        private final List<Object[]> rows;
        private int cursor = -1;

        private FakeResultSet(String[] columns, List<Object[]> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        private ResultSet asResultSet() {
            // A fresh cursor per execution: a statement may legitimately run several times.
            cursor = -1;
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("next")) {
                    return ++cursor < rows.size();
                }
                if (name.equals("close")) {
                    return null;
                }
                if (name.equals("getObject") && args.length == 1 && args[0] instanceof Integer index) {
                    return valueAt(index);
                }
                if (name.equals("getObject") && args.length == 1 && args[0] instanceof String label) {
                    return valueAt(indexOf(label));
                }
                if (name.equals("getObject") && args.length == 2) {
                    // The PostgreSQL driver refuses a conversion it cannot perform, and the ORM relies on
                    // that behaviour being reported instead of silently producing a wrong value.
                    Object value = valueAt((Integer) args[0]);
                    Class<?> target = (Class<?>) args[1];
                    if (value != null && !target.isInstance(value) && !isWidening(value, target)) {
                        throw new SQLException("conversion to class " + target.getName()
                                + " from " + value.getClass().getSimpleName() + " not supported");
                    }
                    return value;
                }
                return switch (name) {
                    case "isClosed" -> false;
                    case "toString" -> "FakeResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("ResultSet." + name);
                };
            };
            return (ResultSet) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }

        private boolean isWidening(Object value, Class<?> target) {
            return value instanceof Number number && Number.class.isAssignableFrom(target);
        }

        private int indexOf(String label) {
            for (int i = 0; i < columns.length; i++) {
                if (columns[i].equalsIgnoreCase(label)) {
                    return i + 1;
                }
            }
            throw new IllegalArgumentException("Unknown column " + label + " in " + Arrays.toString(columns));
        }

        private Object valueAt(int index) throws SQLException {
            if (cursor < 0 || cursor >= rows.size()) {
                throw new SQLException("ResultSet is not positioned on a row");
            }
            if (index < 1 || index > columns.length) {
                throw new SQLException("Column index " + index + " is out of range");
            }
            return rows.get(cursor)[index - 1];
        }
    }
}