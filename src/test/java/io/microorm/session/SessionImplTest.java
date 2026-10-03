package io.microorm.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.LazyInitializationException;
import io.microorm.exception.OptimisticLockException;
import io.microorm.exception.PersistenceException;
import io.microorm.exception.TransactionRequiredException;
import io.microorm.pool.ConnectionPool;
import io.microorm.pool.PoolConfig;
import io.microorm.pool.PooledDataSource;
import io.microorm.proxy.EntityProxy;
import io.microorm.support.FakeJdbc;
import io.microorm.support.TestEntities;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Session behaviour without a database, driven by {@link FakeJdbc}.
 *
 * <p>These tests assert the SQL that the session issues, which is the part that unit tests can verify
 * honestly. Anything that depends on real PostgreSQL behaviour - constraint violations, lock
 * conflicts between threads, generated identity values - lives in the {@code *IT} suites.
 */
class SessionImplTest {

    private static final String SELECT_USER_BY_ID = "SELECT id, email, name, age, active, bio, created_at, "
            + "version FROM users WHERE id = ?";
    private static final String SELECT_ALL_USERS = "SELECT id, email, name, age, active, bio, created_at, "
            + "version FROM users";
    private static final String SELECT_ALL_ORDERS = "SELECT id, user_id, description, amount, version FROM orders";
    private static final String SELECT_ORDER_BY_ID = SELECT_ALL_ORDERS.substring(0, SELECT_ALL_ORDERS.indexOf(" FROM "))
            + " FROM orders WHERE id = ?";

    private FakeJdbc database;
    private SessionFactory factory;
    private Session session;

    @BeforeEach
    void setUp() {
        database = new FakeJdbc();
        factory = SessionFactory.builder().dataSource(database.dataSource()).build();
        session = factory.openSession();
    }

    @Test
    @DisplayName("find runs one SELECT and materialises the row")
    void findsById() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 0);

        Optional<TestEntities.User> found = session.find(TestEntities.User.class, 1L);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getEmail()).isEqualTo("ann@example.com");
        assertThat(found.orElseThrow().getName()).isEqualTo("Ann");
        assertThat(found.orElseThrow().getAge()).isEqualTo(30);
        assertThat(found.orElseThrow().getVersion()).isZero();
        assertThat(database.sql()).containsExactly(SELECT_USER_BY_ID);
        assertThat(database.statements().get(0).parameters()).containsExactly(1L);
    }

    @Test
    @DisplayName("a second find of the same row is answered from the persistence context")
    void servesSecondFindFromCache() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 0);

        TestEntities.User first = session.find(TestEntities.User.class, 1L).orElseThrow();
        TestEntities.User second = session.find(TestEntities.User.class, 1L).orElseThrow();

        assertThat(second).isSameAs(first);
        assertThat(database.sql()).hasSize(1);
        assertThat(session.statistics().findsIssued()).isEqualTo(1);
        assertThat(session.statistics().cacheHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("findAll returns managed instances and counts the rows it loaded")
    void findsAll() {
        database.onQueries(SELECT_ALL_USERS, List.of(
                new Object[]{1L, "ann@example.com", "Ann", 30, true, null, null, 0},
                new Object[]{2L, "bob@example.com", "Bob", 40, true, null, null, 0}));

        List<TestEntities.User> users = session.findAll(TestEntities.User.class);

        assertThat(users).hasSize(2);
        assertThat(users).extracting(TestEntities.User::getName).containsExactly("Ann", "Bob");
        assertThat(session.statistics().rowsLoaded()).isEqualTo(2);
    }

    @Test
    @DisplayName("findOrThrow reports the missing row")
    void failsWhenMissing() {
        assertThatThrownBy(() -> session.findOrThrow(TestEntities.User.class, 99L))
                .isInstanceOf(io.microorm.exception.EntityNotFoundException.class)
                .hasMessageContaining("User with id 99 was not found");
    }

    @Test
    @DisplayName("find of a null id is empty and issues no SQL")
    void ignoresNullId() {
        assertThat(session.find(TestEntities.User.class, null)).isEmpty();
        assertThat(database.sql()).isEmpty();
    }

    @Test
    @DisplayName("persist requires a transaction")
    void persistRequiresTransaction() {
        TestEntities.User user = new TestEntities.User("ann@example.com", "Ann", 30, true);

        assertThatThrownBy(() -> session.persist(user))
                .isInstanceOf(TransactionRequiredException.class)
                .hasMessageContaining("beginTransaction()");
        assertThat(database.writes()).isEmpty();
    }

    @Test
    @DisplayName("persist issues an INSERT with RETURNING and adopts the generated identifier")
    void insertsWithGeneratedId() {
        database.onQuery("INSERT INTO users (email, name, age, active, bio, version) VALUES (?, ?, ?, ?, ?, ?)"
                        + " RETURNING id", 7L);
        TestEntities.User user = new TestEntities.User("ann@example.com", "Ann", 30, true);
        user.setBio("hello");

        session.beginTransaction();
        session.persist(user);
        session.commit();

        assertThat(user.getId()).isEqualTo(7L);
        FakeJdbc.ExecutedStatement insert = database.statement("INSERT INTO users").orElseThrow();
        assertThat(insert.parameters()).containsExactly("ann@example.com", "Ann", 30, true, "hello", 0);
        assertThat(session.statistics().insertsIssued()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sequence strategy binds the identifier instead of asking for RETURNING")
    void insertsWithSequenceIdentifier() {
        database.withSequence("orders_id_seq").withSequenceValue("orders_id_seq", 42L);
        database.onUpdate("INSERT INTO orders (id, user_id, description, amount, version) "
                + "VALUES (?, ?, ?, ?, ?)", 1);

        TestEntities.Order order = new TestEntities.Order("book", new BigDecimal("10.00"));
        order.setUser(new TestEntities.User("ann@example.com", "Ann", 30, true));
        ((TestEntities.User) order.getUser()).setId(3L);

        session.beginTransaction();
        session.persist(order);
        session.commit();

        assertThat(order.getId()).isEqualTo(42L);
        assertThat(database.sql()).contains("SELECT nextval('orders_id_seq')");
        FakeJdbc.ExecutedStatement insert = database.statement("INSERT INTO orders").orElseThrow();
        assertThat(insert.parameters()).containsExactly(42L, 3L, "book", new BigDecimal("10.00"), null);
    }

    @Test
    @DisplayName("AUTO falls back to the identity column when no sequence exists")
    void autoStrategyUsesIdentityWithoutSequence() {
        database.onQuery("INSERT INTO users (email, name, age, active, bio, version) VALUES (?, ?, ?, ?, ?, ?)"
                + " RETURNING id", 11L);

        session.beginTransaction();
        session.persist(new TestEntities.User("ann@example.com", "Ann", 30, true));
        session.commit();

        assertThat(database.sql()).doesNotContain("nextval");
        assertThat(database.sql()).anyMatch(sql -> sql.contains("RETURNING id"));
    }

    @Test
    @DisplayName("persist refuses an entity that is already managed")
    void refusesDuplicateInsert() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 0);
        TestEntities.User loaded = session.find(TestEntities.User.class, 1L).orElseThrow();

        session.beginTransaction();

        assertThatThrownBy(() -> session.persist(loaded))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("already managed");
    }

    @Test
    @DisplayName("changing one field produces an UPDATE with exactly that column and the version")
    void updatesOnlyDirtyColumns() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 3);
        TestEntities.User user = session.find(TestEntities.User.class, 1L).orElseThrow();

        session.beginTransaction();
        user.setName("Anna");
        session.commit();

        assertThat(database.writes()).hasSize(1);
        FakeJdbc.ExecutedStatement update = database.writes().get(0);
        assertThat(update.sql()).isEqualTo("UPDATE users SET name = ?, version = ? "
                + "WHERE id = ? AND version = ?");
        assertThat(update.parameters()).containsExactly("Anna", 4, 1L, 3);
        assertThat(user.getVersion()).isEqualTo(4);
        assertThat(session.statistics().updatesIssued()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unchanged entity is not written at all")
    void skipsUnchangedEntity() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 3);
        session.find(TestEntities.User.class, 1L);

        session.beginTransaction();
        session.commit();

        assertThat(database.writes()).isEmpty();
        assertThat(session.statistics().updatesIssued()).isZero();
    }

    @Test
    @DisplayName("a read inside a transaction flushes pending changes first")
    void flushesBeforeReading() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 0);
        database.onQuery(SELECT_ALL_USERS, 1L, "anna@example.com", "Anna", 30, true, null, null, 1);
        TestEntities.User user = session.find(TestEntities.User.class, 1L).orElseThrow();

        session.beginTransaction();
        user.setName("Anna");
        List<TestEntities.User> all = session.findAll(TestEntities.User.class);

        assertThat(all).hasSize(1);
        // The UPDATE happens before the SELECT: that is the whole point of flushing before a read.
        assertThat(database.sql()).containsExactly(SELECT_USER_BY_ID,
                "UPDATE users SET name = ?, version = ? WHERE id = ? AND version = ?", SELECT_ALL_USERS);
    }

    @Test
    @DisplayName("an UPDATE that affects no rows is reported as an optimistic lock failure")
    void detectsLostUpdate() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 3);
        database.onUpdate("UPDATE users SET name = ?, version = ? WHERE id = ? AND version = ?", 0);
        TestEntities.User user = session.find(TestEntities.User.class, 1L).orElseThrow();

        session.beginTransaction();
        user.setName("Anna");

        assertThatThrownBy(session::flush)
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("affected no rows")
                .hasMessageContaining("id 1");
        assertThat(session.hasActiveTransaction()).isTrue();
    }

    @Test
    @DisplayName("a failed flush dooms the transaction instead of leaving half applied work")
    void doomsTransactionOnFailure() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 3);
        database.onUpdate("UPDATE users SET name = ?, version = ? WHERE id = ? AND version = ?", 0);
        TestEntities.User user = session.find(TestEntities.User.class, 1L).orElseThrow();

        session.beginTransaction();
        user.setName("Anna");
        assertThatThrownBy(session::flush).isInstanceOf(OptimisticLockException.class);

        assertThatThrownBy(session::flush)
                .isInstanceOf(io.microorm.exception.TransactionRequiredException.class)
                .hasMessageContaining("marked rollback-only");
    }

    @Test
    @DisplayName("a DELETE that affects no rows because of the version is an optimistic lock failure")
    void detectsLostDelete() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 3);
        database.onUpdate("DELETE FROM users WHERE id = ? AND version = ?", 0);
        TestEntities.User user = session.find(TestEntities.User.class, 1L).orElseThrow();

        session.beginTransaction();
        session.remove(user);

        assertThatThrownBy(session::flush)
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("Delete of User(users)");
    }

    @Test
    @DisplayName("remove issues a version guarded DELETE")
    void deletes() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 3);
        TestEntities.User user = session.find(TestEntities.User.class, 1L).orElseThrow();

        session.beginTransaction();
        session.remove(user);
        session.commit();

        assertThat(database.writes()).hasSize(1);
        assertThat(database.writes().get(0).sql()).isEqualTo("DELETE FROM users WHERE id = ? AND version = ?");
        assertThat(database.writes().get(0).parameters()).containsExactly(1L, 3);
        assertThat(session.statistics().deletesIssued()).isEqualTo(1);
    }

    @Test
    @DisplayName("merge of a detached entity updates the managed instance")
    void mergesDetachedEntity() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 1);
        TestEntities.User managed = session.find(TestEntities.User.class, 1L).orElseThrow();

        TestEntities.User detached = new TestEntities.User("ann@example.com", "Anna", 31, false);
        detached.setId(1L);

        session.beginTransaction();
        Object merged = session.merge(detached);
        session.commit();

        assertThat(merged).isSameAs(managed);
        assertThat(managed.getName()).isEqualTo("Anna");
        assertThat(managed.getAge()).isEqualTo(31);
        assertThat(managed.getActive()).isFalse();
        // Only the fields the detached copy actually changed take part in the UPDATE.
        assertThat(database.statement("UPDATE users").orElseThrow().sql())
                .isEqualTo("UPDATE users SET name = ?, age = ?, active = ?, version = ? "
                        + "WHERE id = ? AND version = ?");
    }

    @Test
    @DisplayName("merge of an unknown row inserts it")
    void mergesAsInsertWhenUnknown() {
        database.onQuery("INSERT INTO users (id, email, name, age, active, bio, version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", 1);
        TestEntities.User detached = new TestEntities.User("ann@example.com", "Ann", 30, true);
        detached.setId(1L);

        session.beginTransaction();
        session.merge(detached);
        session.commit();

        assertThat(database.statement("INSERT INTO users").orElseThrow().parameters())
                .containsExactly(1L, "ann@example.com", "Ann", 30, true, null, 0);
    }

    @Test
    @DisplayName("merge without an identifier behaves like persist")
    void mergesTransientEntity() {
        database.onQuery("INSERT INTO users (email, name, age, active, bio, version) VALUES (?, ?, ?, ?, ?, ?)"
                + " RETURNING id", 5L);
        TestEntities.User transientUser = new TestEntities.User("ann@example.com", "Ann", 30, true);

        session.beginTransaction();
        Object merged = session.merge(transientUser);
        session.commit();

        assertThat(merged).isSameAs(transientUser);
        assertThat(transientUser.getId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("a lazy @ManyToOne is not loaded while the owning row is being read")
    void resolvesLazyAssociationAsProxy() {
        database.onQuery(SELECT_ALL_ORDERS, 1L, 42L, "book", new BigDecimal("10.00"), 0L);
        database.onQuery(SELECT_USER_BY_ID, 42L, "ann@example.com", "Ann", 30, true, null, null, 0);

        List<TestEntities.Order> orders = session.findAll(TestEntities.Order.class);
        TestEntities.Order order = orders.get(0);

        assertThat(order.getUser()).isInstanceOf(EntityProxy.class);
        assertThat(((EntityProxy) order.getUser()).isMicroOrmInitialized()).isFalse();
        assertThat(database.sql()).containsExactly(SELECT_ALL_ORDERS);

        assertThat(order.getUser().getName()).isEqualTo("Ann");
        assertThat(((EntityProxy) order.getUser()).isMicroOrmInitialized()).isTrue();
        assertThat(database.sql()).containsExactly(SELECT_ALL_ORDERS, SELECT_USER_BY_ID);
    }

    @Test
    @DisplayName("a null foreign key becomes null, not a proxy")
    void resolvesNullAssociation() {
        database.onQuery(SELECT_ALL_ORDERS, 1L, null, "book", new BigDecimal("10.00"), 0L);

        TestEntities.Order order = session.findAll(TestEntities.Order.class).get(0);

        assertThat(order.getUser()).isNull();
    }

    @Test
    @DisplayName("getReference does not hit the database until a property is read")
    void getReferenceIsLazy() {
        TestEntities.User reference = session.getReference(TestEntities.User.class, 9L);

        assertThat(reference).isInstanceOf(EntityProxy.class);
        assertThat(reference.getId()).isEqualTo(9L);
        assertThat(database.sql()).isEmpty();

        database.onQuery(SELECT_USER_BY_ID, 9L, "ann@example.com", "Ann", 30, true, null, null, 0);
        assertThat(reference.getEmail()).isEqualTo("ann@example.com");
        assertThat(database.sql()).containsExactly(SELECT_USER_BY_ID);
    }

    @Test
    @DisplayName("getReference of a managed row returns the managed instance itself")
    void getReferenceReturnsManagedInstance() {
        database.onQuery(SELECT_USER_BY_ID, 9L, "ann@example.com", "Ann", 30, true, null, null, 0);
        TestEntities.User managed = session.find(TestEntities.User.class, 9L).orElseThrow();

        assertThat(session.getReference(TestEntities.User.class, 9L)).isSameAs(managed);
    }

    @Test
    @DisplayName("a proxy dereferenced after close() fails with LazyInitializationException")
    void failsAfterClose() {
        TestEntities.User reference = session.getReference(TestEntities.User.class, 9L);

        session.close();

        assertThatThrownBy(reference::getEmail)
                .isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("already closed");
        assertThat(reference.getId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("clear drops the persistence context, so the next find hits the database again")
    void clearEvictsContext() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 0);
        TestEntities.User first = session.find(TestEntities.User.class, 1L).orElseThrow();

        session.clear();
        TestEntities.User second = session.find(TestEntities.User.class, 1L).orElseThrow();

        assertThat(second).isNotSameAs(first);
        assertThat(database.sql()).hasSize(2);
    }

    @Test
    @DisplayName("a nested rollback undoes the inner work and keeps the outer transaction usable")
    void nestedRollbackKeepsOuterTransaction() {
        database.onQuery("INSERT INTO users (email, name, age, active, bio, version) VALUES (?, ?, ?, ?, ?, ?)"
                + " RETURNING id", 1L);
        database.onQuery(SELECT_ALL_USERS, 1L, "ann@example.com", "Ann", 30, true, null, null, 0);

        session.beginTransaction();
        session.persist(new TestEntities.User("ann@example.com", "Ann", 30, true));
        session.beginTransaction();
        session.rollback();

        assertThat(session.hasActiveTransaction()).isTrue();
        assertThat(database.sql()).contains("SAVEPOINT microorm_sp_1", "ROLLBACK TO microorm_sp_1");

        session.findAll(TestEntities.User.class);
        session.commit();

        assertThat(database.sql()).endsWith("COMMIT");
        assertThat(database.countStatements("INSERT INTO users")).isEqualTo(1);
    }

    @Test
    @DisplayName("closing the session rolls back an unfinished transaction and releases the connection")
    void rollsBackOnClose() {
        database.onQuery("INSERT INTO users (email, name, age, active, bio, version) VALUES (?, ?, ?, ?, ?, ?)"
                + " RETURNING id", 1L);
        session.beginTransaction();
        session.persist(new TestEntities.User("ann@example.com", "Ann", 30, true));
        session.flush();

        session.close();

        assertThat(database.sql()).endsWith("ROLLBACK");
        assertThat(session.isOpen()).isFalse();
        assertThat(session.statistics().insertsIssued()).isEqualTo(1);
    }

    @Test
    @DisplayName("using a closed session fails")
    void rejectsUseAfterClose() {
        session.close();

        assertThatThrownBy(() -> session.findAll(TestEntities.User.class))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("Session is closed");
        assertThatThrownBy(() -> session.beginTransaction())
                .isInstanceOf(PersistenceException.class);
        assertThat(session.isOpen()).isFalse();
    }

    @Test
    @DisplayName("the session returns its connection to the pool when it is closed")
    void releasesConnectionToPool() {
        try (ConnectionPool pool = new ConnectionPool(database.dataSource(),
                PoolConfig.builder().minSize(1).maxSize(1).build())) {
            try (Session pooled = SessionFactory.builder().pool(pool).build().openSession()) {
                pooled.findAll(TestEntities.User.class);
                assertThat(pool.metrics().active()).isEqualTo(1);
            }

            assertThat(pool.metrics().active()).isZero();
            assertThat(pool.metrics().idle()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a broken statement invalidates the pooled connection instead of reusing it")
    void invalidatesBrokenConnection() {
        ConnectionPool pool = new ConnectionPool(database.dataSource(),
                PoolConfig.builder().minSize(1).maxSize(2).build());
        try {
            SessionFactory sessionFactory = SessionFactory.builder().pool(pool).build();
            database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 3);
            database.onUpdate("UPDATE users SET name = ?, version = ? WHERE id = ? AND version = ?", 0);
            try (Session broken = sessionFactory.openSession()) {
                broken.beginTransaction();
                broken.find(TestEntities.User.class, 1L).orElseThrow().setName("Anna");

                // A statement that failed leaves the transaction unusable, so the connection must not
                // go back into the pool for somebody else.
                assertThatThrownBy(broken::flush)
                        .isInstanceOf(OptimisticLockException.class);
            }
            assertThat(pool.metrics().discarded()).isPositive();
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("the pool is owned by the factory and closed with it")
    void closesOwnedPool() {
        ConnectionPool pool = new ConnectionPool(database.dataSource(),
                PoolConfig.builder().minSize(1).build());
        SessionFactory sessionFactory = SessionFactory.builder().pool(pool).build();
        PooledDataSource dataSource = new PooledDataSource(pool);

        sessionFactory.close();

        assertThat(dataSource.pool().metrics().total()).isZero();
    }

    @Test
    @DisplayName("statistics count everything the session did")
    void reportsStatistics() {
        database.onQuery(SELECT_USER_BY_ID, 1L, "ann@example.com", "Ann", 30, true, null, null, 0);
        database.onUpdate("UPDATE users SET name = ?, version = ? WHERE id = ? AND version = ?", 1);

        session.beginTransaction();
        TestEntities.User user = session.find(TestEntities.User.class, 1L).orElseThrow();
        user.setName("Anna");
        session.flush();
        session.find(TestEntities.User.class, 1L);

        SessionStatistics statistics = session.statistics();
        assertThat(statistics.findsIssued()).isEqualTo(1);
        assertThat(statistics.rowsLoaded()).isEqualTo(1);
        assertThat(statistics.cacheHits()).isEqualTo(1);
        assertThat(statistics.updatesIssued()).isEqualTo(1);
        assertThat(statistics.flushes()).isEqualTo(1);
        assertThat(statistics.writes()).isEqualTo(1);
        assertThat(statistics.toString()).contains("selects=1");
    }

    @Test
    @DisplayName("a factory needs a data source")
    void requiresDataSource() {
        assertThatThrownBy(() -> SessionFactory.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dataSource");
    }

    @Test
    @DisplayName("a factory can prepare metadata for the DDL exporter up front")
    void registersEntitiesEagerly() {
        SessionFactory sessionFactory = SessionFactory.builder()
                .dataSource(database.dataSource())
                .entities(TestEntities.User.class, TestEntities.Order.class)
                .build();

        assertThat(sessionFactory.knownEntities()).hasSize(2);
        assertThat(sessionFactory.schemaExporter().script(sessionFactory.knownEntities()))
                .contains("CREATE TABLE users (")
                .contains("CREATE TABLE orders (");
        assertThat(sessionFactory.pool()).isEmpty();
        assertThat(sessionFactory.isolationLevel())
                .isEqualTo(io.microorm.transaction.IsolationLevel.READ_COMMITTED);
        assertThat(sessionFactory.metadata().size()).isEqualTo(2);
        assertThat(sessionFactory.sqlGenerator()).isNotNull();
        assertThat(sessionFactory.idGenerators()).isNotNull();
        assertThat(sessionFactory.proxyFactory()).isNotNull();
    }
}