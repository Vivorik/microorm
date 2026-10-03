package io.microorm.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.example.Order;
import io.microorm.example.User;
import io.microorm.exception.EntityNotFoundException;
import io.microorm.exception.TransactionRequiredException;
import io.microorm.session.Session;
import io.microorm.session.SessionFactory;
import io.microorm.session.SessionStatistics;
import io.microorm.support.PostgresFixture;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The core CRUD scenarios against a real PostgreSQL: persist, find, update, remove, plus the two
 * guarantees that make an ORM worth using - identity of managed instances and dirty checking.
 */
class CrudIT {

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
    void cleanDatabase() {
        PostgresFixture.truncate(factory);
    }

    private User persistUser(String email, String name, int age) {
        try (Session session = factory.openSession()) {
            User user = new User(email, name, age, true);
            session.beginTransaction();
            session.persist(user);
            session.commit();
            return user;
        }
    }

    @Test
    @DisplayName("persist writes the row, assigns the identity column and reads it back")
    void persistsAndReadsBack() {
        User saved = persistUser("ann@example.com", "Ann", 30);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isZero();
        assertThat((Long) PostgresFixture.queryScalar(factory, "SELECT count(*) FROM users")).isEqualTo(1L);

        try (Session session = factory.openSession()) {
            User loaded = session.findOrThrow(User.class, saved.getId());

            assertThat(loaded.getEmail()).isEqualTo("ann@example.com");
            assertThat(loaded.getName()).isEqualTo("Ann");
            assertThat(loaded.getAge()).isEqualTo(30);
            assertThat(loaded.getActive()).isTrue();
            assertThat(loaded.getCreatedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("a column excluded from INSERT is filled by the database default")
    void readsDatabaseDefault() {
        User saved = persistUser("ann@example.com", "Ann", 30);

        try (Session session = factory.openSession()) {
            User loaded = session.findOrThrow(User.class, saved.getId());

            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getCreatedAt().getYear()).isGreaterThanOrEqualTo(2024);
        }
    }

    @Test
    @DisplayName("a field is updated with an UPDATE that mentions only the changed column")
    void updatesOnlyChangedColumns() {
        User saved = persistUser("ann@example.com", "Ann", 30);

        try (Session session = factory.openSession()) {
            User loaded = session.findOrThrow(User.class, saved.getId());
            session.beginTransaction();
            loaded.setName("Anna");
            session.commit();

            assertThat(loaded.getVersion()).isEqualTo(1);
        }

        assertThat((String) PostgresFixture.queryScalar(factory, "SELECT name FROM users"))
                .isEqualTo("Anna");
        assertThat((Integer) PostgresFixture.queryScalar(factory, "SELECT age FROM users")).isEqualTo(30);
    }

    @Test
    @DisplayName("remove deletes the row, verified with raw SQL")
    void removesRow() {
        User saved = persistUser("ann@example.com", "Ann", 30);

        try (Session session = factory.openSession()) {
            User loaded = session.findOrThrow(User.class, saved.getId());
            session.beginTransaction();
            session.remove(loaded);
            session.commit();
        }

        assertThat((Long) PostgresFixture.queryScalar(factory, "SELECT count(*) FROM users")).isZero();
        try (Session session = factory.openSession()) {
            assertThat(session.find(User.class, saved.getId())).isEmpty();
            assertThatThrownBy(() -> session.findOrThrow(User.class, saved.getId()))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Test
    @DisplayName("the same row is represented by the same object within one session")
    void firstLevelCacheKeepsIdentity() {
        User saved = persistUser("ann@example.com", "Ann", 30);

        try (Session session = factory.openSession()) {
            User first = session.findOrThrow(User.class, saved.getId());
            User second = session.findOrThrow(User.class, saved.getId());

            assertThat(second).isSameAs(first);

            first.setName("Anna");
            session.beginTransaction();
            session.flush();
            session.commit();

            List<User> all = session.findAll(User.class);
            assertThat(all).hasSize(1);
            assertThat(all.get(0)).isSameAs(first);
            assertThat(all.get(0).getName()).isEqualTo("Anna");

            SessionStatistics statistics = session.statistics();
            assertThat(statistics.findsIssued()).isEqualTo(2);
            assertThat(statistics.cacheHits()).isGreaterThanOrEqualTo(1);
            assertThat(statistics.updatesIssued()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an unchanged entity produces no UPDATE at all")
    void doesNotWriteUnchangedEntities() {
        User saved = persistUser("ann@example.com", "Ann", 30);

        try (Session session = factory.openSession()) {
            session.findOrThrow(User.class, saved.getId());
            session.beginTransaction();
            session.commit();

            assertThat(session.statistics().updatesIssued()).isZero();
        }
        assertThat((Integer) PostgresFixture.queryScalar(factory, "SELECT version FROM users")).isZero();
    }

    @Test
    @DisplayName("merge attaches a detached entity to the session")
    void mergesDetachedEntity() {
        User saved = persistUser("ann@example.com", "Ann", 30);

        User detached = new User("ann@example.com", "Anna", 31, false);
        detached.setId(saved.getId());

        try (Session session = factory.openSession()) {
            session.beginTransaction();
            User managed = (User) session.merge(detached);
            session.commit();

            assertThat(managed.getName()).isEqualTo("Anna");
            assertThat(managed.getAge()).isEqualTo(31);
        }

        assertThat((String) PostgresFixture.queryScalar(factory, "SELECT name FROM users"))
                .isEqualTo("Anna");
        assertThat((Integer) PostgresFixture.queryScalar(factory, "SELECT version FROM users")).isEqualTo(1);
    }

    @Test
    @DisplayName("a unique constraint violation surfaces as PersistenceException")
    void reportsConstraintViolation() {
        persistUser("ann@example.com", "Ann", 30);

        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.persist(new User("ann@example.com", "Clone", 20, true));

            assertThatThrownBy(session::commit)
                    .isInstanceOf(io.microorm.exception.PersistenceException.class);
        }
    }

    @Test
    @DisplayName("writes without a transaction are refused")
    void requiresTransactionForWrites() {
        try (Session session = factory.openSession()) {
            assertThatThrownBy(() -> session.persist(new User("ann@example.com", "Ann", 30, true)))
                    .isInstanceOf(TransactionRequiredException.class);
            assertThatThrownBy(session::flush)
                    .isInstanceOf(TransactionRequiredException.class);
        }
        assertThat((Long) PostgresFixture.queryScalar(factory, "SELECT count(*) FROM users")).isZero();
    }

    @Test
    @DisplayName("a not-null column is reported by the database")
    void reportsNotNullViolation() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            User user = new User(null, "Ann", 30, true);
            session.persist(user);

            assertThatThrownBy(session::commit)
                    .isInstanceOf(io.microorm.exception.PersistenceException.class)
                    .hasMessageContaining("Cannot insert User(users)");
        }
    }

    @Test
    @DisplayName("a many-to-one association is stored as a foreign key")
    void storesForeignKey() {
        User user = persistUser("ann@example.com", "Ann", 30);

        try (Session session = factory.openSession()) {
            session.beginTransaction();
            Order order = new Order("book", new BigDecimal("10.50"));
            order.setUser(session.findOrThrow(User.class, user.getId()));
            session.persist(order);
            session.commit();

            assertThat(order.getId()).isNotNull();
        }

        assertThat((Long) PostgresFixture.queryScalar(factory, "SELECT user_id FROM orders"))
                .isEqualTo(user.getId());
    }

    @Test
    @DisplayName("the persistence context is emptied by clear()")
    void clearsContext() {
        User saved = persistUser("ann@example.com", "Ann", 30);

        try (Session session = factory.openSession()) {
            User first = session.findOrThrow(User.class, saved.getId());

            session.clear();
            User second = session.findOrThrow(User.class, saved.getId());

            assertThat(second).isNotSameAs(first);
        }
    }
}