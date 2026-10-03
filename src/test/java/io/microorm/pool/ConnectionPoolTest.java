package io.microorm.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.ConnectionPoolException;
import io.microorm.exception.PersistenceException;
import io.microorm.support.FakeConnections;
import io.microorm.support.MutableClock;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final AtomicInteger physicalCreations = new AtomicInteger();
    private final List<FakeConnections.State> states = new ArrayList<>();
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = FakeConnections.dataSource(physicalCreations, states);
    }

    private PoolConfig.Builder config() {
        return PoolConfig.builder().clock(clock);
    }

    private ConnectionPool pool(PoolConfig poolConfig) {
        return new ConnectionPool(dataSource, poolConfig);
    }

    @Test
    @DisplayName("creates minSize connections at startup so that a broken setup fails fast")
    void prefillsMinimumSize() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(3).maxSize(5).build())) {
            assertThat(pool.metrics()).isEqualTo(new PoolMetrics(3, 0, 3, 0, 3, 0));
            assertThat(physicalCreations).hasValue(3);
        }
    }

    @Test
    @DisplayName("borrowing takes a connection out of the idle set and marks it active")
    void borrowMarksActive() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1).build())) {
            Connection connection = pool.borrow();

            assertThat(pool.metrics().active()).isEqualTo(1);
            assertThat(pool.metrics().idle()).isZero();
            assertThat(pool.openConnections()).isEqualTo(1);
            assertThat(connection).isNotNull();
        }
    }

    @Test
    @DisplayName("closing the wrapper returns the connection instead of closing it")
    void closeReturnsConnectionToPool() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1).build())) {
            Connection connection = pool.borrow();
            connection.close();

            assertThat(connection.isClosed()).isTrue();
            assertThat(pool.metrics().idle()).isEqualTo(1);
            assertThat(pool.metrics().active()).isZero();
            assertThat(states.get(0).closeCount()).isZero();
            assertThat(physicalCreations).hasValue(1);
        }
    }

    @Test
    @DisplayName("closing the wrapper twice returns the connection only once")
    void closeIsIdempotent() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1).build())) {
            Connection connection = pool.borrow();
            connection.close();
            connection.close();

            assertThat(pool.metrics().idle()).isEqualTo(1);
            assertThat(pool.metrics().active()).isZero();
        }
    }

    @Test
    @DisplayName("a reused connection is handed out again instead of opening a new physical one")
    void reusesIdleConnection() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1).build())) {
            pool.borrow().close();
            Connection reused = pool.borrow();

            assertThat(physicalCreations).hasValue(1);
            assertThat(states.get(0).autoCommitResets()).isPositive();
            reused.close();
        }
    }

    @Test
    @DisplayName("a connection can be reused after the clock moved between borrow and release")
    void reusesConnectionAfterTheClockMoved() throws Exception {
        // Regression: returning a connection used to put a refreshed copy into the idle set while the
        // bookkeeping set kept the original, so the second release no longer recognised its connection.
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1).build())) {
            pool.borrow().close();
            clock.advance(Duration.ofSeconds(1));

            Connection reused = pool.borrow();
            reused.close();

            assertThat(pool.metrics().active()).isZero();
            assertThat(pool.metrics().idle()).isEqualTo(1);
            assertThat(pool.metrics().total()).isEqualTo(1);
            assertThat(pool.metrics().discarded()).isZero();
        }
    }

    @Test
    @DisplayName("a connection that fails the health check is replaced by a fresh one")
    void discardsBrokenConnection() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(2).build())) {
            pool.borrow().close();
            states.get(0).breakConnection();

            Connection replacement = pool.borrow();

            assertThat(physicalCreations).hasValue(2);
            assertThat(replacement).isNotNull();
            assertThat(pool.metrics().discarded()).isEqualTo(1);
            replacement.close();
        }
    }

    @Test
    @DisplayName("validation runs the configured health check on every borrow of an idle connection")
    void validatesOnBorrow() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1)
                .validationQuery("SELECT 42").validateOnReturn(false).build())) {
            pool.borrow().close();
            // The pre-filled connection is validated on the first borrow as well, so the baseline
            // is measured after it instead of assuming zero.
            int before = states.get(0).validationCount();
            pool.borrow().close();

            assertThat(states.get(0).validationCount()).isEqualTo(before + 1);
        }
    }

    @Test
    @DisplayName("validateOnReturn checks the connection before it goes back to the idle set")
    void validatesOnReturn() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(2)
                .validateOnBorrow(false).validateOnReturn(true).build())) {
            Connection brokenOnRelease = pool.borrow();
            states.get(0).breakConnection();
            brokenOnRelease.close();

            assertThat(pool.metrics().idle()).isZero();
            assertThat(states.get(0).closeCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("validation can be switched off in both directions")
    void validationIsConfigurable() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1)
                .validateOnBorrow(false).validateOnReturn(false).build())) {
            pool.borrow().close();
            pool.borrow().close();

            assertThat(states.get(0).validationCount()).isZero();
        }
    }

    @Test
    @DisplayName("a connection closed by the driver is not handed out again")
    void discardsServerSideClosedConnection() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(2).build())) {
            pool.borrow().close();
            states.get(0).closeServerSide();

            Connection connection = pool.borrow();

            assertThat(physicalCreations).hasValue(2);
            connection.close();
        }
    }

    @Test
    @DisplayName("idle connections are retired after idleTimeout")
    void retiresIdleConnections() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(2)
                .idleTimeout(Duration.ofMinutes(10)).build())) {
            pool.borrow().close();
            clock.advance(Duration.ofMinutes(11));

            Connection connection = pool.borrow();

            assertThat(physicalCreations).hasValue(2);
            assertThat(states.get(0).closeCount()).isEqualTo(1);
            assertThat(pool.metrics().discarded()).isEqualTo(1);
            connection.close();
        }
    }

    @Test
    @DisplayName("connections are retired after maxLifetime even though they were used recently")
    void retiresExpiredLifetime() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(2)
                .maxLifetime(Duration.ofMinutes(30)).idleTimeout(Duration.ofHours(1)).build())) {
            pool.borrow().close();
            // Used recently, so idleTimeout is not reached, but the connection has outlived its lifetime.
            clock.advance(Duration.ofMinutes(31));

            Connection connection = pool.borrow();

            assertThat(physicalCreations).hasValue(2);
            assertThat(states.get(0).closeCount()).isEqualTo(1);
            connection.close();
        }
    }

    @Test
    @DisplayName("invalidate() disposes of the connection when it is released")
    void invalidateDiscardsConnection() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(2).build())) {
            Connection connection = pool.borrow();
            pool.invalidate(connection, "SQLException in test");
            connection.close();

            assertThat(pool.metrics().idle()).isZero();
            assertThat(states.get(0).closeCount()).isEqualTo(1);
            assertThat(physicalCreations).hasValue(1);
        }
    }

    @Test
    @DisplayName("a saturated pool fails fast with ConnectionPoolException instead of hanging")
    void failsWhenSaturated() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1)
                .connectionTimeout(Duration.ofMillis(100)).build())) {
            Connection first = pool.borrow();

            long startedAt = System.nanoTime();
            assertThatThrownBy(pool::borrow)
                    .isInstanceOf(ConnectionPoolException.class)
                    .hasMessageContaining("No connection available within PT0.1S")
                    .hasMessageContaining("maxSize=1");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(90));
            assertThat(pool.metrics().waiting()).isZero();
            first.close();
        }
    }

    @Test
    @DisplayName("a waiting borrower is woken up as soon as a connection is returned")
    void handsConnectionToWaitingBorrower() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).maxSize(1)
                .connectionTimeout(Duration.ofSeconds(5)).build())) {
            Connection held = pool.borrow();
            CountDownLatch borrowing = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Connection> pending = executor.submit(() -> {
                    borrowing.countDown();
                    return pool.borrow();
                });
                assertThat(borrowing.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(50);
                assertThat(pool.metrics().waiting()).isEqualTo(1);

                held.close();

                try (Connection received = pending.get(5, TimeUnit.SECONDS)) {
                    assertThat(received).isNotNull();
                    assertThat(pool.metrics().active()).isEqualTo(1);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("concurrent borrowers never receive the same physical connection twice")
    void handsOutDistinctConnectionsConcurrently() throws Exception {
        int threads = 12;
        try (ConnectionPool pool = pool(config().minSize(0).maxSize(3)
                .connectionTimeout(Duration.ofSeconds(5)).build())) {
            Set<Connection> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(executor.submit(() -> {
                        start.await();
                        try (Connection connection = pool.borrow()) {
                            return seen.add(connection);
                        }
                    }));
                }
                start.countDown();
                for (Future<Boolean> result : results) {
                    assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                executor.shutdownNow();
            }
            assertThat(pool.metrics().total()).isLessThanOrEqualTo(3);
            assertThat(pool.openConnections()).isEqualTo(pool.metrics().total());
        }
    }

    @Test
    @DisplayName("releasing a connection that is not a pooled connection is rejected")
    void rejectsUnpooledConnection() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).build())) {
            Connection foreign = FakeConnections.create(new FakeConnections.State());

            assertThatThrownBy(() -> pool.release(foreign))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not a pooled connection");
        }
    }

    @Test
    @DisplayName("releasing a connection borrowed from a different pool is rejected")
    void rejectsForeignConnection() throws Exception {
        try (ConnectionPool first = pool(config().minSize(1).build());
             ConnectionPool second = pool(config().minSize(1).build())) {
            Connection foreign = first.borrow();

            assertThatThrownBy(() -> second.release(foreign))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not borrowed from this pool");
            foreign.close();
        }
    }

    @Test
    @DisplayName("using a returned connection fails instead of silently hitting another session")
    void rejectsUseAfterReturn() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).build())) {
            Connection connection = pool.borrow();
            connection.close();

            assertThatThrownBy(connection::createStatement)
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("already been returned to the pool");
        }
    }

    @Test
    @DisplayName("closing the pool closes every physical connection and rejects further borrows")
    void closeDisposesEverything() throws Exception {
        ConnectionPool pool = pool(config().minSize(2).build());
        pool.borrow();
        pool.close();

        assertThat(states).allSatisfy(state -> assertThat(state.closeCount()).isEqualTo(1));
        assertThatThrownBy(pool::borrow)
                .isInstanceOf(ConnectionPoolException.class)
                .hasMessageContaining("pool is closed");
        assertThat(pool.metrics().total()).isZero();
    }

    @Test
    @DisplayName("a failing data source is reported as PersistenceException")
    void reportsConnectionFailures() throws Exception {
        DataSource broken = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    throw new java.sql.SQLException("no route to host");
                });

        assertThatThrownBy(() -> new ConnectionPool(broken, PoolConfig.builder().build()))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("Cannot open a physical connection")
                .hasCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    @DisplayName("metrics report saturation of the pool")
    void reportsSaturation() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(0).maxSize(1).build())) {
            assertThat(pool.metrics().saturated()).isFalse();

            try (Connection connection = pool.borrow()) {
                assertThat(pool.metrics().saturated()).isTrue();
                assertThat(pool.metrics().toString()).contains("active=1");
            }
            assertThat(pool.metrics().saturated()).isFalse();
        }
    }

    @Test
    @DisplayName("validate() borrows a connection and runs the health check")
    void exposesHealthCheck() throws Exception {
        try (ConnectionPool pool = pool(config().minSize(1).build())) {
            assertThat(pool.validate("SELECT 1")).isTrue();
            assertThat(pool.metrics().active()).isZero();
        }
    }
}