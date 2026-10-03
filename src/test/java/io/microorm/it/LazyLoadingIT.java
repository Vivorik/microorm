package io.microorm.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.example.Order;
import io.microorm.example.User;
import io.microorm.exception.LazyInitializationException;
import io.microorm.proxy.EntityProxy;
import io.microorm.session.Session;
import io.microorm.session.SessionFactory;
import io.microorm.support.DockerAvailability;
import io.microorm.support.PostgresFixture;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lazy loading against a real PostgreSQL.
 *
 * <p>The interesting assertion is not that lazy loading works, but that it does <em>not</em> work: a
 * reference must not touch the database until a property other than the identifier is read, and it must
 * fail predictably once the session is gone.
 */
class LazyLoadingIT {

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

    private Long persistUserId() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            User user = new User("ann@example.com", "Ann", 30, true);
            session.persist(user);
            session.commit();
            return user.getId();
        }
    }

    private Long persistOrder(Long userId) {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            Order order = new Order("book", new BigDecimal("10.50"));
            order.setUser(session.findOrThrow(User.class, userId));
            session.persist(order);
            session.commit();
            return order.getId();
        }
    }

    @Test
    @DisplayName("getReference returns a proxy without querying, and the id needs no query either")
    void referenceIsLazy() {
        Long id = persistUserId();

        try (Session session = factory.openSession()) {
            int selectsBefore = session.statistics().findsIssued();

            User reference = session.getReference(User.class, id);

            assertThat(reference).isInstanceOf(EntityProxy.class);
            assertThat(((EntityProxy) reference).isMicroOrmInitialized()).isFalse();
            assertThat(reference.getId()).isEqualTo(id);
            assertThat(session.statistics().findsIssued()).isEqualTo(selectsBefore);

            assertThat(reference.getName()).isEqualTo("Ann");
            assertThat(((EntityProxy) reference).isMicroOrmInitialized()).isTrue();
            assertThat(session.statistics().findsIssued()).isEqualTo(selectsBefore + 1);
        }
    }

    @Test
    @DisplayName("the proxy loads at most once, no matter how many properties are read")
    void loadsOnce() {
        Long id = persistUserId();

        try (Session session = factory.openSession()) {
            User reference = session.getReference(User.class, id);
            int before = session.statistics().findsIssued();

            assertThat(reference.getName()).isEqualTo("Ann");
            assertThat(reference.getEmail()).isEqualTo("ann@example.com");
            assertThat(reference.getAge()).isEqualTo(30);
            assertThat(reference.getActive()).isTrue();

            assertThat(session.statistics().findsIssued()).isEqualTo(before + 1);
        }
    }

    @Test
    @DisplayName("a lazy association is a proxy until it is dereferenced")
    void lazyAssociation() {
        Long userId = persistUserId();
        persistOrder(userId);

        try (Session session = factory.openSession()) {
            int selectsBefore = session.statistics().findsIssued();
            List<Order> orders = session.findAll(Order.class);
            Order order = orders.get(0);

            assertThat(order.getUser()).isInstanceOf(EntityProxy.class);
            assertThat(((EntityProxy) order.getUser()).isMicroOrmInitialized()).isFalse();
            assertThat(order.getUser().getId()).isEqualTo(userId);
            assertThat(session.statistics().findsIssued()).isEqualTo(selectsBefore + 1);

            assertThat(order.getUser().getName()).isEqualTo("Ann");
            assertThat(((EntityProxy) order.getUser()).isMicroOrmInitialized()).isTrue();
        }
    }

    @Test
    @DisplayName("an association already in the context is not loaded again")
    void reusesManagedAssociation() {
        Long userId = persistUserId();
        persistOrder(userId);

        try (Session session = factory.openSession()) {
            User loaded = session.findOrThrow(User.class, userId);
            int selectsBefore = session.statistics().findsIssued();

            Order order = session.findAll(Order.class).get(0);
            User viaOrder = order.getUser();

            assertThat(viaOrder).isSameAs(loaded);
            assertThat(session.statistics().findsIssued()).isEqualTo(selectsBefore + 1);
        }
    }

    @Test
    @DisplayName("a proxy that is used after close() fails with LazyInitializationException")
    void failsAfterClose() {
        Long id = persistUserId();

        User reference;
        try (Session session = factory.openSession()) {
            reference = session.getReference(User.class, id);
        }

        assertThatThrownBy(reference::getName)
                .isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("already closed");
        assertThat(reference.getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("a proxy for a row that does not exist reports EntityNotFoundException")
    void failsForMissingRow() {
        try (Session session = factory.openSession()) {
            User reference = session.getReference(User.class, 999_999L);

            assertThatThrownBy(reference::getName)
                    .isInstanceOf(io.microorm.exception.EntityNotFoundException.class);
        }
    }

    @Test
    @DisplayName("writing through a proxy updates the row")
    void writesThroughProxy() {
        Long id = persistUserId();

        try (Session session = factory.openSession()) {
            User reference = session.getReference(User.class, id);
            reference.getName();
            reference.setName("Anna");
            session.beginTransaction();
            session.commit();
        }

        assertThat((String) PostgresFixture.queryScalar(factory, "SELECT name FROM users"))
                .isEqualTo("Anna");
    }
}