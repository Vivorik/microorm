package io.microorm.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.example.User;
import io.microorm.exception.OptimisticLockException;
import io.microorm.session.Session;
import io.microorm.session.SessionFactory;
import io.microorm.support.DockerAvailability;
import io.microorm.support.PostgresFixture;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Optimistic locking against a real PostgreSQL.
 *
 * <p>The scenario is the one that matters in practice: two sessions read the same row, both change it,
 * and the second writer must lose instead of silently overwriting the first one.
 */
class OptimisticLockingIT {

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
    @DisplayName("two sessions updating the same row: the second one fails")
    void detectsConcurrentUpdate() {
        Long id = persistUser();

        try (Session first = factory.openSession(); Session second = factory.openSession()) {
            User inFirst = first.findOrThrow(User.class, id);
            User inSecond = second.findOrThrow(User.class, id);

            first.beginTransaction();
            inFirst.setName("Anna");
            first.commit();

            second.beginTransaction();
            inSecond.setName("Bob");
            assertThatThrownBy(second::commit)
                    .isInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("affected no rows");
        }

        assertThat((String) PostgresFixture.queryScalar(factory, "SELECT name FROM users"))
                .isEqualTo("Anna");
        assertThat((Integer) PostgresFixture.queryScalar(factory, "SELECT version FROM users")).isEqualTo(1);
    }

    @Test
    @DisplayName("two threads updating the same row: exactly one wins")
    void detectsConcurrentUpdateFromThreads() throws Exception {
        Long id = persistUser();
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch mayWrite = new CountDownLatch(1);

        Callable<Boolean> write = (Callable<Boolean>) () -> {
            bothRead.countDown();
            bothRead.await(5, TimeUnit.SECONDS);
            mayWrite.await(5, TimeUnit.SECONDS);
            try (Session session = factory.openSession()) {
                User user = session.findOrThrow(User.class, id);
                user.setName("Thread " + Thread.currentThread().getName());
                session.beginTransaction();
                session.commit();
                return true;
            } catch (OptimisticLockException expected) {
                return false;
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<String> failure = new AtomicReference<>();
        try {
            Future<Boolean> first = executor.submit(write);
            Future<Boolean> second = executor.submit(write);
            bothRead.await(5, TimeUnit.SECONDS);
            mayWrite.countDown();

            int winners = (Boolean.TRUE.equals(first.get(20, TimeUnit.SECONDS)) ? 1 : 0)
                    + (Boolean.TRUE.equals(second.get(20, TimeUnit.SECONDS)) ? 1 : 0);

            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(failure.get()).isNull();
        assertThat((Integer) PostgresFixture.queryScalar(factory, "SELECT version FROM users")).isEqualTo(1);
    }

    @Test
    @DisplayName("the version is incremented on every update")
    void incrementsVersion() {
        Long id = persistUser();

        try (Session session = factory.openSession()) {
            for (int i = 1; i <= 3; i++) {
                User user = session.findOrThrow(User.class, id);
                session.beginTransaction();
                user.setName("Ann " + i);
                session.commit();
                assertThat(user.getVersion()).isEqualTo(i);
            }
        }

        assertThat((Integer) PostgresFixture.queryScalar(factory, "SELECT version FROM users")).isEqualTo(3);
    }

    @Test
    @DisplayName("a failed update dooms the transaction, and the session reports it")
    void doomsTransactionOnConflict() {
        Long id = persistUser();

        try (Session first = factory.openSession(); Session second = factory.openSession()) {
            User inFirst = first.findOrThrow(User.class, id);
            User inSecond = second.findOrThrow(User.class, id);

            first.beginTransaction();
            inFirst.setName("Anna");
            first.commit();

            second.beginTransaction();
            inSecond.setName("Bob");
            assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);

            assertThatThrownBy(second::flush)
                    .isInstanceOf(io.microorm.exception.TransactionRequiredException.class)
                    .hasMessageContaining("marked rollback-only");
            second.rollback();
        }
    }

    @Test
    @DisplayName("deleting a row that was changed by somebody else fails")
    void detectsConcurrentDelete() {
        Long id = persistUser();

        try (Session first = factory.openSession(); Session second = factory.openSession()) {
            first.findOrThrow(User.class, id);
            User inSecond = second.findOrThrow(User.class, id);

            first.beginTransaction();
            first.findOrThrow(User.class, id).setName("Anna");
            first.commit();

            second.beginTransaction();
            second.remove(inSecond);
            assertThatThrownBy(second::commit)
                    .isInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("Delete of User(users)");
        }

        assertThat((Long) PostgresFixture.queryScalar(factory, "SELECT count(*) FROM users")).isEqualTo(1);
    }
}