package io.microorm.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.PersistenceException;
import io.microorm.exception.TransactionRequiredException;
import io.microorm.pool.ConnectionPool;
import io.microorm.pool.PoolConfig;
import io.microorm.pool.PooledDataSource;
import io.microorm.support.FakeConnections;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionManagerTest {

    private final AtomicInteger creations = new AtomicInteger();
    private final List<FakeConnections.State> states = new ArrayList<>();
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = FakeConnections.dataSource(creations, states);
    }

    private TransactionManager manager() {
        return new TransactionManager(dataSource, IsolationLevel.READ_COMMITTED, (connection, reason) -> { });
    }

    @Test
    @DisplayName("borrows the connection lazily and reuses it for the whole session")
    void borrowsOneConnectionPerSession() throws Exception {
        try (TransactionManager manager = manager()) {
            assertThat(creations).hasValue(0);

            Connection first = manager.connection();
            Connection second = manager.connection();

            assertThat(creations).hasValue(1);
            assertThat(second).isSameAs(first);
        }
    }

    @Test
    @DisplayName("closing returns the connection to the pool")
    void returnsConnectionToPool() throws Exception {
        ConnectionPool pool = new ConnectionPool(dataSource, PoolConfig.builder().minSize(0).maxSize(1).build());
        try {
            TransactionManager manager = new TransactionManager(
                    new PooledDataSource(pool), IsolationLevel.READ_COMMITTED, pool::invalidate);
            manager.connection();
            assertThat(pool.metrics().active()).isEqualTo(1);

            manager.close();

            assertThat(pool.metrics().active()).isZero();
            assertThat(pool.metrics().idle()).isEqualTo(1);
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("closing the manager rolls back an unfinished transaction")
    void rollsBackOnClose() throws Exception {
        try (TransactionManager manager = manager()) {
            manager.begin();
        }

        assertThat(states.get(0).events()).contains("rollback");
    }

    @Test
    @DisplayName("closing the manager does not roll back an already committed transaction")
    void keepsCommittedWork() throws Exception {
        try (TransactionManager manager = manager()) {
            manager.begin().commit();
        }

        assertThat(states.get(0).events()).contains("commit").doesNotContain("rollback");
    }

    @Test
    @DisplayName("begin() nests when a transaction is already running")
    void nestsOnSecondBegin() throws Exception {
        try (TransactionManager manager = manager()) {
            Transaction outer = manager.begin();
            Transaction inner = manager.begin();

            assertThat(inner).isSameAs(outer);
            assertThat(outer.depth()).isEqualTo(2);
            assertThat(manager.hasActiveTransaction()).isTrue();
            assertThat(manager.current()).contains(outer);

            inner.rollback();
            assertThat(outer.depth()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an explicit isolation level is used for the top level transaction only")
    void appliesExplicitIsolation() throws Exception {
        try (TransactionManager manager = manager()) {
            manager.begin(IsolationLevel.SERIALIZABLE);

            assertThat(states.get(0).isolationLevel()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
            assertThat(manager.current().orElseThrow().isolationLevel()).isEqualTo(IsolationLevel.SERIALIZABLE);
        }
    }

    @Test
    @DisplayName("a nested begin ignores a different isolation level: JDBC cannot change it mid-flight")
    void ignoresIsolationWhenNesting() throws Exception {
        try (TransactionManager manager = manager()) {
            manager.begin();
            manager.begin(IsolationLevel.SERIALIZABLE);

            assertThat(states.get(0).isolationLevel()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(manager.current().orElseThrow().depth()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("requireActiveTransaction explains what to do instead of failing obscurely")
    void explainsMissingTransaction() {
        try (TransactionManager manager = manager()) {
            assertThatThrownBy(() -> manager.requireActiveTransaction("persist an entity"))
                    .isInstanceOf(TransactionRequiredException.class)
                    .hasMessageContaining("Cannot persist an entity without an active transaction")
                    .hasMessageContaining("beginTransaction()");
        }
    }

    @Test
    @DisplayName("a finished transaction is no longer reported as current")
    void hidesFinishedTransaction() throws Exception {
        try (TransactionManager manager = manager()) {
            manager.begin().commit();

            assertThat(manager.current()).isEmpty();
            assertThat(manager.hasActiveTransaction()).isFalse();
        }
    }

    @Test
    @DisplayName("using a closed manager is rejected")
    void rejectsUseAfterClose() {
        TransactionManager manager = manager();
        manager.close();

        assertThatThrownBy(manager::connection)
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("session is closed");
    }

    @Test
    @DisplayName("a broken connection is handed to the invalidator instead of the pool")
    void invalidatesBrokenConnection() throws Exception {
        List<String> invalidated = new ArrayList<>();
        ConnectionPool pool = new ConnectionPool(dataSource, PoolConfig.builder().minSize(0).maxSize(1).build());
        try {
            TransactionManager manager = new TransactionManager(
                    new PooledDataSource(pool), IsolationLevel.READ_COMMITTED,
                    (connection, reason) -> {
                        invalidated.add(reason);
                        pool.invalidate(connection, reason);
                    });
            Connection borrowed = manager.connection();

            manager.markConnectionBroken("SQLException: current transaction is aborted");
            manager.close();

            assertThat(invalidated).containsExactly("SQLException: current transaction is aborted");
            assertThat(pool.metrics().idle()).isZero();
            assertThat(borrowed).isNotNull();
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("a failing data source is reported when the connection is needed")
    void reportsConnectionFailure() {
        DataSource broken = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    throw new java.sql.SQLException("connection refused");
                });
        try (TransactionManager manager = new TransactionManager(
                broken, IsolationLevel.READ_COMMITTED, (connection, reason) -> { })) {
            assertThatThrownBy(manager::connection)
                    .isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("Cannot borrow a connection")
                    .hasCauseInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    @DisplayName("defaultIsolationLevel is exposed for diagnostics")
    void exposesDefaultIsolation() {
        try (TransactionManager manager = manager()) {
            assertThat(manager.defaultIsolationLevel()).isEqualTo(IsolationLevel.READ_COMMITTED);
        }
    }
}