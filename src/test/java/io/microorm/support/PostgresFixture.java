package io.microorm.support;

import io.microorm.pool.ConnectionPool;
import io.microorm.pool.PoolConfig;
import io.microorm.session.SessionFactory;
import io.microorm.transaction.IsolationLevel;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container shared by every integration test.
 *
 * <p>The container is started once per JVM: starting PostgreSQL takes seconds, and every test only
 * needs the data to be isolated, which {@link #truncate} takes care of.
 *
 * <p>When no Docker daemon is reachable the container cannot start and the tests are skipped instead of
 * failing - see {@link DockerAvailability}. There is a second way to run them: point the tests at an
 * already running PostgreSQL with {@code -Dmicroorm.jdbcUrl=jdbc:postgresql://host:port/db}, which is
 * how they were verified on a machine without Docker. The schema is applied from the same
 * {@code docker/init.sql} in both modes.
 */
public final class PostgresFixture {

    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    /** Set {@code -Dmicroorm.jdbcUrl=...} to run the integration tests against an existing database. */
    private static final String EXTERNAL_URL = System.getProperty("microorm.jdbcUrl", System.getenv("MICROORM_JDBC_URL"));

    private static final String USER = property("microorm.jdbcUser", "MICROORM_JDBC_USER", "microorm");

    private static final String PASSWORD = property("microorm.jdbcPassword", "MICROORM_JDBC_PASSWORD", "microorm");

    private static final class Holder {
        private static final PostgreSQLContainer<?> CONTAINER = startContainer();
    }

    private PostgresFixture() {
    }

    /** @return {@code true} when the tests use an externally provided database */
    public static boolean usesExternalDatabase() {
        return EXTERNAL_URL != null;
    }

    /** @return {@code true} when the tests can run at all: either an external database or Docker */
    public static boolean isAvailable() {
        return usesExternalDatabase() || DockerAvailability.isAvailable();
    }

    /** Skips the calling test when neither an external database nor Docker is available. */
    public static void assumeDatabase() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isAvailable(),
                "No PostgreSQL available: pass -Dmicroorm.jdbcUrl=... or start Docker");
    }

    private static String property(String systemProperty, String environmentVariable, String fallback) {
        String value = System.getProperty(systemProperty);
        return value != null ? value : System.getenv().getOrDefault(environmentVariable, fallback);
    }

    private static PostgreSQLContainer<?> startContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("microorm")
                .withUsername("microorm")
                .withPassword("microorm")
                .withInitScript("docker/init.sql");
        container.start();
        return container;
    }

    /** @return the container, only meaningful when {@link #usesExternalDatabase()} is false */
    public static PostgreSQLContainer<?> container() {
        return Holder.CONTAINER;
    }

    /** @return JDBC URL of the database under test */
    public static String jdbcUrl() {
        return usesExternalDatabase() ? EXTERNAL_URL : Holder.CONTAINER.getJdbcUrl();
    }

    /** @return user name of the database under test */
    public static String user() {
        return usesExternalDatabase() ? USER : Holder.CONTAINER.getUsername();
    }

    /** @return password of the database under test */
    public static String password() {
        return usesExternalDatabase() ? PASSWORD : Holder.CONTAINER.getPassword();
    }

    /**
     * Applies {@code docker/init.sql} to an external database, so both modes start from the same schema.
     */
    private static void applyInitScript() {
        // The script is applied once per test class, so it has to be idempotent: a previous class may
        // have left the tables behind. Dropping first keeps both modes independent of execution order.
        String script = "DROP TABLE IF EXISTS orders, users, tickets CASCADE;\n"
                + "DROP SEQUENCE IF EXISTS orders_id_seq;\nDROP SEQUENCE IF EXISTS tickets_id_seq;\n" + readInitScript();
        // Split on the statement terminator, not on newlines: CREATE TABLE spans several lines.
        String[] commands = script.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"))
                .split(";");
        try (Connection connection = rawDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            for (String command : commands) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot apply the init script to " + jdbcUrl(), e);
        }
    }

    private static String readInitScript() {
        try (java.io.InputStream stream = PostgresFixture.class.getResourceAsStream("/docker/init.sql")) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read /docker/init.sql from the test resources", e);
        }
    }

    /**
     * Creates a session factory with its own pool, mimicking an application that configures a pool.
     *
     * @return a started factory
     */
    public static SessionFactory sessionFactory() {
        return SessionFactory.builder()
                .connectionPool(rawDataSource(), PoolConfig.builder()
                        .minSize(1)
                        .maxSize(4)
                        .idleTimeout(Duration.ofMinutes(1))
                        .build())
                .isolationLevel(IsolationLevel.READ_COMMITTED)
                .build();
    }

    /**
     * @param schema schema to put in front of the session's search path
     * @return the JDBC URL of the database under test, pinned to one schema
     */
    public static String jdbcUrlWithSchema(String schema) {
        String separator = jdbcUrl().contains("?") ? "&" : "?";
        return jdbcUrl() + separator + "currentSchema=" + schema;
    }

    /** @return an unpooled data source pointing at the database under test */
    public static PGSimpleDataSource rawDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl());
        dataSource.setUser(user());
        dataSource.setPassword(password());
        return dataSource;
    }

    /**
     * Prepares the schema once per JVM.
     *
     * <p>Testcontainers applies the init script itself; an external database needs it applied here.
     */
    public static void initialiseSchema() {
        if (usesExternalDatabase()) {
            applyInitScript();
        }
    }

    /** @return the pool of the factory, so a test can assert on pool metrics */
    public static ConnectionPool poolOf(SessionFactory factory) {
        return factory.pool().orElseThrow(() -> new IllegalStateException("The factory owns no pool"));
    }

    /**
     * Empties the tables, so tests do not depend on each other's data.
     *
     * @param factory factory whose connection is used
     */
    public static void truncate(SessionFactory factory) {
        execute(factory, "TRUNCATE orders, users RESTART IDENTITY CASCADE");
    }

    /**
     * Runs a statement outside of MicroORM, for assertions that need raw SQL.
     *
     * <p>{@code execute} is used rather than {@code executeUpdate} because several statements here are
     * {@code SELECT}s that return rows, and PostgreSQL rejects an update call on those.
     *
     * @param factory session factory
     * @param sql     statement to run
     * @return number of rows the statement produced
     */
    public static int execute(SessionFactory factory, String sql) {
        try (Connection connection = factory.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            return statement.execute(sql) ? 1 : statement.getUpdateCount();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute: " + sql, e);
        }
    }

    /**
     * Reads a single value with raw SQL.
     *
     * @param factory session factory
     * @param sql     statement returning one column
     * @return the value, or {@code null} for SQL NULL
     */
    public static Object queryScalar(SessionFactory factory, String sql) {
        try (Connection connection = factory.dataSource().getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getObject(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query: " + sql, e);
        }
    }
}