package io.microorm.support;

import io.microorm.pool.ConnectionPool;
import io.microorm.pool.PoolConfig;
import io.microorm.session.SessionFactory;
import io.microorm.transaction.IsolationLevel;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container shared by every integration test.
 *
 * <p>The container is started once per JVM: starting PostgreSQL takes seconds, and every test only
 * needs the data to be isolated, which {@link #truncate(String...)} takes care of.
 *
 * <p>When no Docker daemon is reachable the container cannot start, and the tests that depend on it are
 * skipped instead of failing - see {@code DockerAvailability}.
 */
public final class PostgresFixture {

    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    private static final class Holder {
        private static final PostgreSQLContainer<?> CONTAINER = start();
    }

    private PostgresFixture() {
    }

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("microorm")
                .withUsername("microorm")
                .withPassword("microorm")
                .withInitScript("docker/init.sql");
        container.start();
        return container;
    }

    /** @return the started container */
    public static PostgreSQLContainer<?> container() {
        return Holder.CONTAINER;
    }

    /** @return JDBC URL of the container */
    public static String jdbcUrl() {
        return Holder.CONTAINER.getJdbcUrl();
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

    /** @return an unpooled data source pointing at the container */
    public static PGSimpleDataSource rawDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl());
        dataSource.setUser(Holder.CONTAINER.getUsername());
        dataSource.setPassword(Holder.CONTAINER.getPassword());
        return dataSource;
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
     * @param factory session factory
     * @param sql     statement to run
     * @return number of affected rows
     */
    public static int execute(SessionFactory factory, String sql) {
        try (Connection connection = factory.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
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