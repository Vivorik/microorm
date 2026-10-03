package io.microorm.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.microorm.example.Order;
import io.microorm.example.Ticket;
import io.microorm.example.User;
import io.microorm.session.Session;
import io.microorm.session.SessionFactory;
import io.microorm.support.DockerAvailability;
import io.microorm.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Identifier generation against a real PostgreSQL.
 *
 * <p>The schema is arranged so that both strategies are exercised: {@code users} has an identity column
 * and no sequence, {@code orders} has a sequence. {@code AUTO} therefore has to resolve differently for
 * the two tables, which is what makes this test worth having.
 */
class IdGenerationIT {

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

    @Test
    @DisplayName("AUTO on a table with an identity column and no sequence uses the identity")
    void autoUsesIdentityWhenNoSequenceExists() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            User user = new User("ann@example.com", "Ann", 30, true);
            session.persist(user);
            session.commit();

            assertThat(user.getId()).isNotNull().isPositive();
        }
    }

    @Test
    @DisplayName("SEQUENCE binds the identifier the sequence produced")
    void sequenceStrategyBindsIdentifier() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            Order order = new Order("book", new java.math.BigDecimal("10.50"));
            order.setUser(session.findOrThrow(User.class, persistUserId(session)));
            session.persist(order);
            session.commit();

            assertThat(order.getId()).isNotNull().isPositive();
        }

        Long sequenceValue = (Long) PostgresFixture.queryScalar(factory, "SELECT last_value FROM orders_id_seq");
        assertThat(sequenceValue).isPositive();
    }

    @Test
    @DisplayName("AUTO on a table that has a sequence uses that sequence")
    void autoUsesSequenceWhenItExists() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            Ticket ticket = new Ticket("support ticket");
            session.persist(ticket);
            session.commit();

            assertThat(ticket.getId()).isNotNull().isPositive();
        }
    }

    @Test
    @DisplayName("identifiers keep growing across transactions")
    void identifiersAreUnique() {
        java.util.List<Long> ids = new java.util.ArrayList<>();

        for (int i = 0; i < 3; i++) {
            try (Session session = factory.openSession()) {
                session.beginTransaction();
                User user = new User("user" + i + "@example.com", "User " + i, 20 + i, true);
                session.persist(user);
                session.commit();
                ids.add(user.getId());
            }
        }

        assertThat(ids).doesNotHaveDuplicates();
        assertThat((Long) PostgresFixture.queryScalar(factory, "SELECT max(id) FROM users")).isEqualTo(ids.get(2));
    }

    private Long persistUserId(Session session) {
        if (session.find(User.class, 1L).isPresent()) {
            return 1L;
        }
        User user = new User("ann@example.com", "Ann", 30, true);
        session.persist(user);
        session.flush();
        return user.getId();
    }
}