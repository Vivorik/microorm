package io.microorm.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.example.User;
import io.microorm.exception.TransactionRequiredException;
import io.microorm.session.Session;
import io.microorm.session.SessionFactory;
import io.microorm.support.DockerAvailability;
import io.microorm.support.PostgresFixture;
import io.microorm.transaction.IsolationLevel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Transaction semantics against a real PostgreSQL, including savepoint based nesting. */
class TransactionIT {

    private static SessionFactory factory;

    @BeforeAll
    static void startDatabase() {
        DockerAvailability.assumeDocker();
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

    private long countUsers() {
        return (Long) PostgresFixture.queryScalar(factory, "SELECT count(*) FROM users");
    }

    private void insert(Session session, String email) {
        session.persist(new User(email, "Ann", 30, true));
    }

    @Test
    @DisplayName("commit makes the change visible to other sessions")
    void commits() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            insert(session, "ann@example.com");
            session.commit();
        }

        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    @DisplayName("rollback discards every change of the transaction")
    void rollsBack() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            insert(session, "ann@example.com");
            session.flush();
            assertThat(countUsers()).isZero();

            session.rollback();
        }

        assertThat(countUsers()).isZero();
    }

    @Test
    @DisplayName("an unfinished transaction is rolled back when the session closes")
    void rollsBackOnClose() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            insert(session, "ann@example.com");
            session.flush();
        }

        assertThat(countUsers()).isZero();
    }

    @Test
    @DisplayName("a savepoint rollback undoes the inner work and keeps the outer transaction")
    void rollsBackToSavepoint() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            insert(session, "outer@example.com");
            session.flush();

            session.beginTransaction();
            insert(session, "inner@example.com");
            session.flush();
            session.rollback();

            // The outer transaction is still usable: PostgreSQL rolls back to the savepoint only.
            insert(session, "after@example.com");
            session.commit();
        }

        assertThat(countUsers()).isEqualTo(2);
        assertThat((Long) PostgresFixture.queryScalar(factory,
                "SELECT count(*) FROM users WHERE email = 'inner@example.com'")).isZero();
        assertThat((Long) PostgresFixture.queryScalar(factory,
                "SELECT count(*) FROM users WHERE email = 'after@example.com'")).isEqualTo(1);
    }

    @Test
    @DisplayName("committing a nested transaction keeps its work for the outer transaction")
    void commitsNestedTransaction() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.beginTransaction();
            insert(session, "inner@example.com");
            session.commit();

            session.rollback();

            // Rolling back the outer transaction discards the inner work as well.
            assertThat(countUsers()).isZero();
        }
    }

    @Test
    @DisplayName("a rollback-only transaction refuses further work")
    void refusesWorkAfterRollbackOnly() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.beginTransaction().markRollbackOnly();

            assertThatThrownBy(() -> insert(session, "ann@example.com"))
                    .isInstanceOf(TransactionRequiredException.class)
                    .hasMessageContaining("marked rollback-only");

            session.rollback();
        }
    }

    @Test
    @DisplayName("the isolation level is applied to the connection")
    void appliesIsolationLevel() {
        try (Session session = factory.openSession()) {
            session.beginTransaction(IsolationLevel.SERIALIZABLE);

            assertThat(session.hasActiveTransaction()).isTrue();
            assertThat(session.beginTransaction().isNested()).isTrue();
            session.rollback();
        }
    }

    @Test
    @DisplayName("reads inside a transaction see the changes flushed before them")
    void flushesBeforeRead() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            insert(session, "ann@example.com");

            assertThat(session.findAll(User.class)).hasSize(1);
            assertThat(countUsers()).isZero();
        }
    }

    @Test
    @DisplayName("a transaction with REPEATABLE READ keeps its own snapshot")
    void repeatableReadHidesConcurrentChanges() {
        try (Session writer = factory.openSession()) {
            writer.beginTransaction();
            insert(writer, "ann@example.com");
            writer.commit();
        }

        try (Session reader = factory.openSession()) {
            reader.beginTransaction(IsolationLevel.REPEATABLE_READ);
            assertThat(reader.findAll(User.class)).hasSize(1);

            try (Session other = factory.openSession()) {
                other.beginTransaction();
                insert(other, "bob@example.com");
                other.commit();
            }

            assertThat(reader.findAll(User.class)).hasSize(1);
            reader.rollback();
        }

        assertThat(countUsers()).isEqualTo(2);
    }
}