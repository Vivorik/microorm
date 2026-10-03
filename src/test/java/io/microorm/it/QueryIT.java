package io.microorm.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.example.User;
import io.microorm.exception.MappingException;
import io.microorm.query.ComparisonOperator;
import io.microorm.query.Query;
import io.microorm.query.SortDirection;
import io.microorm.session.Session;
import io.microorm.session.SessionFactory;
import io.microorm.support.DockerAvailability;
import io.microorm.support.PostgresFixture;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The query API against a real PostgreSQL, including the SQL injection scenarios. */
class QueryIT {

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

    private void seed() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.persist(new User("ann@example.com", "Ann", 30, true));
            session.persist(new User("bob@example.com", "Bob", 17, true));
            session.persist(new User("cid@example.com", "Cid", 42, false));
            session.commit();
        }
    }

    @Test
    @DisplayName("conditions, ordering and pagination are applied by PostgreSQL")
    void runsTheFullExample() {
        seed();

        try (Session session = factory.openSession()) {
            List<User> adults = session.createQuery(User.class)
                    .where("age", ">", 18)
                    .and("active", "=", true)
                    .orderBy("name", SortDirection.ASC)
                    .limit(10)
                    .offset(0)
                    .list();

            assertThat(adults).extracting(User::getName).containsExactly("Ann");
        }
    }

    @Test
    @DisplayName("or, in, isNull and isNotNull narrow the result")
    void supportsAllOperators() {
        seed();

        try (Session session = factory.openSession()) {
            assertThat(session.createQuery(User.class)
                    .where("age", ">", 18).or("age", "<", 18)
                    .count()).isEqualTo(3);

            assertThat(session.createQuery(User.class)
                    .in("name", "Ann", "Bob").count()).isEqualTo(2);

            assertThat(session.createQuery(User.class)
                    .notIn("name", "Ann").count()).isEqualTo(2);

            assertThat(session.createQuery(User.class)
                    .isNull("bio").count()).isEqualTo(3);

            assertThat(session.createQuery(User.class)
                    .isNotNull("bio").count()).isZero();

            assertThat(session.createQuery(User.class)
                    .where("email", ComparisonOperator.LIKE, "%@example.com").count()).isEqualTo(3);

            assertThat(session.createQuery(User.class)
                    .where("age", ">=", 30).and("age", "<=", 42).count()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("orderBy with several keys and offset return the expected page")
    void ordersAndPaginates() {
        seed();

        try (Session session = factory.openSession()) {
            List<User> firstPage = session.createQuery(User.class)
                    .orderBy("name", SortDirection.DESC)
                    .limit(2).list();

            assertThat(firstPage).extracting(User::getName).containsExactly("Cid", "Bob");

            List<User> secondPage = session.createQuery(User.class)
                    .orderBy("name", SortDirection.DESC)
                    .limit(2).offset(2).list();

            assertThat(secondPage).extracting(User::getName).containsExactly("Ann");
        }
    }

    @Test
    @DisplayName("count and exists agree with the loaded rows")
    void countsAndChecksExistence() {
        seed();

        try (Session session = factory.openSession()) {
            Query<User> query = session.createQuery(User.class).where("active", "=", true);

            assertThat(query.count()).isEqualTo(2);
            assertThat(query.exists()).isTrue();
            assertThat(session.createQuery(User.class).where("name", "=", "Nobody").exists()).isFalse();
        }
    }

    @Test
    @DisplayName("first and single behave as documented")
    void returnsFirstAndSingle() {
        seed();

        try (Session session = factory.openSession()) {
            assertThat(session.createQuery(User.class).where("name", "=", "Ann").single().getAge())
                    .isEqualTo(30);
            assertThat(session.createQuery(User.class).orderByAsc("name").first().orElseThrow().getName())
                    .isEqualTo("Ann");
            assertThat(session.createQuery(User.class).where("name", "=", "Nobody").first()).isEmpty();
            assertThatThrownBy(() -> session.createQuery(User.class).single())
                    .isInstanceOf(io.microorm.exception.NonUniqueResultException.class)
                    .hasMessageContaining("Expected exactly one User(users)");
        }
    }

    @Test
    @DisplayName("a value full of SQL cannot escape the bind parameter")
    void resistsSqlInjectionThroughValues() {
        String hostile = "'; DROP TABLE users; --";

        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.persist(new User(hostile, "Bobby Tables", 30, true));
            session.commit();

            List<User> found = session.createQuery(User.class).where("name", "=", hostile).list();

            assertThat(found).hasSize(1);
            assertThat(found.get(0).getEmail()).isEqualTo(hostile);
            assertThat(session.createQuery(User.class).where("email", ComparisonOperator.LIKE, "%' OR 1=1 --")
                    .count()).isZero();
        }

        // The table is still there, which is the whole point.
        assertThat((Long) PostgresFixture.queryScalar(factory, "SELECT count(*) FROM users")).isEqualTo(1);
    }

    @Test
    @DisplayName("a value with a wildcard is treated as data, LIKE excepted")
    void treatsWildcardsAsData() {
        seed();

        try (Session session = factory.openSession()) {
            assertThat(session.createQuery(User.class).where("name", "=", "%").count()).isZero();
            assertThat(session.createQuery(User.class).where("name", ComparisonOperator.LIKE, "%o%").count())
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an unknown column is rejected before PostgreSQL sees it")
    void rejectsUnknownColumn() {
        seed();

        try (Session session = factory.openSession()) {
            assertThatThrownBy(() -> session.createQuery(User.class)
                    .where("1=1; DROP TABLE users", "=", 1).list())
                    .isInstanceOf(MappingException.class)
                    .hasMessageContaining("Unknown column");
            assertThatThrownBy(() -> session.createQuery(User.class)
                    .orderBy("password", SortDirection.ASC))
                    .isInstanceOf(MappingException.class);
        }

        assertThat((Long) PostgresFixture.queryScalar(factory, "SELECT count(*) FROM users")).isEqualTo(3);
    }

    @Test
    @DisplayName("queries return managed instances, so a change made through them is flushed")
    void returnsManagedInstances() {
        seed();

        try (Session session = factory.openSession()) {
            session.beginTransaction();
            User ann = session.createQuery(User.class).where("name", "=", "Ann").single();
            ann.setName("Anna");
            session.commit();

            assertThat(session.findOrThrow(User.class, ann.getId())).isSameAs(ann);
        }

        assertThat((String) PostgresFixture.queryScalar(
                factory, "SELECT name FROM users WHERE email = 'ann@example.com'")).isEqualTo("Anna");
    }
}