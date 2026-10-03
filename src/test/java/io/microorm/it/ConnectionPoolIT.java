package io.microorm.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.example.User;
import io.microorm.exception.ConnectionPoolException;
import io.microorm.pool.ConnectionPool;
import io.microorm.pool.PoolConfig;
import io.microorm.session.Session;
import io.microorm.session.SessionFactory;
import io.microorm.support.MutableClock;
import io.microorm.support.PostgresFixture;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The connection pool against a real PostgreSQL, including expiry driven by an injected clock. */
class ConnectionPoolIT {

    private static SessionFactory factory;

    @BeforeAll
    static void startDatabase() {
        PostgresFixture.assumeDatabase();
        PostgresFixture.initialiseSchema();
        factory = PostgresFixture.sessionFactory();
    }

    @AfterAll
    static void stopDatabase() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void cleanDatabase() throws Exception {
        PostgresFixture.truncate(factory);
    }

    private Long persistUser() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            User user = new User("ann@example.com", "Ann", 30, true);
            session.persist(user);
            session.commit();
            return user.getId();
        }
    }

    @Test
    @DisplayName("a session takes a connection from the pool and returns it on close")
    void returnsConnectionOnClose() throws Exception {
        ConnectionPool pool = PostgresFixture.poolOf(factory);
        Long id = persistUser();

        Session session = factory.openSession();
        session.findAll(User.class);
        assertThat(pool.metrics().active()).isEqualTo(1);
        assertThat(pool.metrics().idle()).isEqualTo(0);

        session.close();

        assertThat(pool.metrics().active()).isZero();
        assertThat(pool.metrics().idle()).isEqualTo(1);
        assertThat(pool.metrics().total()).isEqualTo(1);
        assertThat(id).isNotNull();
    }

    @Test
    @DisplayName("two sessions never share the same physical connection")
    void doesNotShareConnections() throws SQLException {
        try (ConnectionPool fresh = new ConnectionPool(PostgresFixture.rawDataSource(),
                PoolConfig.builder().minSize(0).maxSize(2).build())) {
            SessionFactory local = SessionFactory.builder().pool(fresh).build();
            try (Session first = local.openSession(); Session second = local.openSession()) {
                first.findAll(User.class);
                second.findAll(User.class);

                assertThat(fresh.metrics().active()).isEqualTo(2);
                assertThat(fresh.metrics().total()).isEqualTo(2);
            }

            assertThat(fresh.metrics().idle()).isEqualTo(2);
            assertThat(fresh.openConnections()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a saturated pool fails fast instead of waiting forever")
    void failsWhenSaturated() throws SQLException {
        try (ConnectionPool pool = new ConnectionPool(PostgresFixture.rawDataSource(),
                PoolConfig.builder().minSize(1).maxSize(1)
                .connectionTimeout(Duration.ofMillis(200)).build())) {
            pool.borrow();

            assertThatThrownBy(pool::borrow)
                    .isInstanceOf(ConnectionPoolException.class)
                    .hasMessageContaining("No connection available");
        }
    }

    @Test
    @DisplayName("a connection is validated before it is handed out again")
    void validatesOnBorrow() throws Exception {
        MutableClock clock = new MutableClock(java.time.Instant.now());
        try (ConnectionPool pool = new ConnectionPool(PostgresFixture.rawDataSource(),
                PoolConfig.builder().minSize(1).maxSize(1).clock(clock).build())) {
            pool.borrow().close();
            long before = pool.metrics().created();

            assertThat(pool.borrow()).isNotNull();
            assertThat(pool.metrics().created()).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("an expired connection is replaced, and the new one is a real connection")
    void replacesExpiredConnections() throws Exception {
        MutableClock clock = new MutableClock(java.time.Instant.now());
        try (ConnectionPool pool = new ConnectionPool(PostgresFixture.rawDataSource(),
                PoolConfig.builder().minSize(1).maxSize(2)
                .maxLifetime(Duration.ofMinutes(5))
                .idleTimeout(Duration.ofHours(1))
                .clock(clock).build())) {
            var first = pool.borrow();
            assertThat(first.isValid(1)).isTrue();
            first.close();

            clock.advance(Duration.ofMinutes(10));

            try (var second = pool.borrow()) {
                assertThat(second.isValid(1)).isTrue();
            }
            assertThat(pool.metrics().discarded()).isPositive();
        }
    }

    @Test
    @DisplayName("a connection killed by PostgreSQL is discarded instead of being handed out")
    void discardsServerSideClosedConnections() throws SQLException {
        try (ConnectionPool pool = new ConnectionPool(PostgresFixture.rawDataSource(),
                PoolConfig.builder().minSize(1).maxSize(2).build())) {
            var borrowed = pool.borrow();
            int backendPid;
            try (var statement = borrowed.createStatement();
                 var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                backendPid = rows.getInt(1);
            }
            // Terminating exactly this backend is what a dropped network connection looks like to the
            // driver: the socket stays open, the server is gone.
            PostgresFixture.execute(factory, "SELECT pg_terminate_backend(" + backendPid + ")");
            borrowed.close();

            try (var replacement = pool.borrow()) {
                assertThat(replacement.isValid(1)).isTrue();
            }
            assertThat(pool.metrics().discarded()).isPositive();
        }
    }

    @Test
    @DisplayName("closing the pool closes every physical connection")
    void closesEverything() throws SQLException {
        ConnectionPool pool = new ConnectionPool(PostgresFixture.rawDataSource(),
                PoolConfig.builder().minSize(2).build());
        var connection = pool.borrow();

        pool.close();

        assertThat(connection.isClosed()).isTrue();
        assertThat(pool.metrics().total()).isZero();
        assertThatThrownBy(pool::borrow).isInstanceOf(ConnectionPoolException.class);
    }

    @Test
    @DisplayName("the health check runs through a pooled connection")
    void validatesConnection() throws SQLException {
        try (ConnectionPool pool = new ConnectionPool(PostgresFixture.rawDataSource(),
                PoolConfig.builder().minSize(1).build())) {
            assertThat(pool.validate("SELECT 1")).isTrue();
        }
    }
}