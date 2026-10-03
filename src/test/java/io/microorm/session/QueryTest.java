package io.microorm.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.MappingException;
import io.microorm.exception.NonUniqueResultException;
import io.microorm.query.ComparisonOperator;
import io.microorm.query.SortDirection;
import io.microorm.support.FakeJdbc;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.microorm.example.User;
import io.microorm.example.Order;

/**
 * The query builder is verified against the exact SQL it produces, because that is the contract this
 * API promises: values are bound, column names come from metadata, and clauses are rendered by the
 * builder rather than by the caller.
 */
class QueryTest {

    private static final String SELECT = "SELECT id, email, name, age, active, bio, created_at, version FROM users ";
    private static final String SELECT_ORDERS = "SELECT id, user_id, description, amount, version FROM orders ";

    private FakeJdbc database;
    private SessionFactory factory;
    private Session session;

    @BeforeEach
    void setUp() {
        database = new FakeJdbc();
        factory = SessionFactory.builder().dataSource(database.dataSource()).build();
        session = factory.openSession();
    }

    private List<User> query() {
        return session.createQuery(User.class).list();
    }

    @Test
    @DisplayName("a query without conditions selects everything")
    void selectsEverything() {
        database.onQueries(SELECT.trim(), List.<Object[]>of(
                new Object[]{1L, "ann@example.com", "Ann", 30, true, null, null, 0},
                new Object[]{2L, "bob@example.com", "Bob", 40, true, null, null, 0}));

        List<User> users = query();

        assertThat(users).hasSize(2);
        assertThat(database.sql()).containsExactly("SELECT id, email, name, age, active, bio, created_at, "
                + "version FROM users");
    }

    @Test
    @DisplayName("where/and render placeholders and bind the values in order")
    void rendersWhereClause() {
        database.onQuery(SELECT + "WHERE age > ? AND active = ?", 1L, "ann@example.com", "Ann", 30, true,
                null, null, 0);

        List<User> users = session.createQuery(User.class)
                .where("age", ">", 18)
                .and("active", "=", true)
                .list();

        assertThat(users).hasSize(1);
        assertThat(database.statements().get(0).parameters()).containsExactly(18, true);
    }

    @Test
    @DisplayName("or keeps its own operator and is rendered left to right")
    void rendersOrClause() {
        database.onQuery(SELECT + "WHERE name = ? OR name = ?", 1L, "a@b.c", "Ann", 30, true, null, null, 0);

        session.createQuery(User.class)
                .where("name", "=", "Ann")
                .or("name", ComparisonOperator.EQUAL, "Bob")
                .list();

        assertThat(database.statements().get(0).parameters()).containsExactly("Ann", "Bob");
    }

    @Test
    @DisplayName("in and notIn expand into one placeholder per value")
    void rendersInClauses() {
        database.onQueries(SELECT + "WHERE id IN (?, ?) AND id NOT IN (?)", List.<Object[]>of(
                new Object[]{1L, "a@b.c", "Ann", 30, true, null, null, 0},
                new Object[]{2L, "b@b.c", "Bob", 40, true, null, null, 0}));

        List<User> users = session.createQuery(User.class)
                .in("id", 1L, 2L)
                .notIn("id", 99L)
                .list();

        assertThat(users).hasSize(2);
        assertThat(database.statements().get(0).parameters()).containsExactly(1L, 2L, 99L);
    }

    @Test
    @DisplayName("isNull and isNotNull bind nothing")
    void rendersUnaryClauses() {
        database.onQuery(SELECT + "WHERE bio IS NULL AND name IS NOT NULL", 1L, "a@b.c", "Ann", 30, true,
                null, null, 0);

        session.createQuery(User.class).isNull("bio").isNotNull("name").list();

        assertThat(database.statements().get(0).parameters()).isEmpty();
    }

    @Test
    @DisplayName("orderBy supports several keys and limit/offset are rendered by the dialect")
    void rendersOrderAndPagination() {
        database.onQuery(SELECT + "ORDER BY name ASC, age DESC LIMIT 10 OFFSET 20",
                1L, "a@b.c", "Ann", 30, true, null, null, 0);

        session.createQuery(User.class)
                .orderBy("name", SortDirection.ASC)
                .orderBy("age", SortDirection.DESC)
                .limit(10)
                .offset(20)
                .list();

        assertThat(database.sql()).containsExactly(SELECT + "ORDER BY name ASC, age DESC LIMIT 10 OFFSET 20");
    }

    @Test
    @DisplayName("orderByAsc is a shortcut, and pagination works without an ORDER BY")
    void rendersPaginationWithoutOrder() {
        database.onQueries(SELECT + "LIMIT 2", List.<Object[]>of(
                new Object[]{1L, "a@b.c", "Ann", 30, true, null, null, 0},
                new Object[]{2L, "b@b.c", "Bob", 40, true, null, null, 0}));

        session.createQuery(User.class).orderByAsc("name").limit(2).list();

        assertThat(database.statements().get(0).parameters()).isEmpty();
    }

    @Test
    @DisplayName("count runs a count(*) with the same clause")
    void counts() {
        database.onQuery("SELECT count(*) FROM users WHERE active = ?", 7L);

        long count = session.createQuery(User.class).where("active", "=", true).count();

        assertThat(count).isEqualTo(7L);
        assertThat(database.statements().get(0).parameters()).containsExactly(true);
    }

    @Test
    @DisplayName("exists only asks whether a row matched")
    void checksExistence() {
        // The same statement is executed twice, but the first call matches and the second does not.
        database.onQuerySequence("SELECT 1 FROM users WHERE name = ? LIMIT 1",
                List.<Object[]>of(new Object[]{1}), List.<Object[]>of());

        assertThat(session.createQuery(User.class).where("name", "=", "Ann").exists()).isTrue();
        assertThat(session.createQuery(User.class).where("name", "=", "Nobody").exists()).isFalse();
        assertThat(database.statements().get(0).parameters()).containsExactly("Ann");
        assertThat(database.sql().get(0)).isEqualTo("SELECT 1 FROM users WHERE name = ? LIMIT 1");
    }

    @Test
    @DisplayName("first adds an implicit limit and returns empty when nothing matched")
    void returnsFirst() {
        database.onQueries(SELECT + "WHERE name = ? LIMIT 1", List.<Object[]>of(
                new Object[]{1L, "a@b.c", "Ann", 30, true, null, null, 0}));

        Optional<User> first = session.createQuery(User.class)
                .where("name", "=", "Ann").first();

        assertThat(first).isPresent();
        assertThat(database.sql().get(0)).endsWith("LIMIT 1");
    }

    @Test
    @DisplayName("single requires exactly one row")
    void returnsSingle() {
        database.onQuery(SELECT + "WHERE name = ?", 1L, "a@b.c", "Ann", 30, true, null, null, 0);

        assertThat(session.createQuery(User.class).where("name", "=", "Ann").single().getName())
                .isEqualTo("Ann");

        database.onQueries(SELECT + "WHERE name = ?", List.<Object[]>of(
                new Object[]{1L, "a@b.c", "Ann", 30, true, null, null, 0},
                new Object[]{2L, "b@b.c", "Bob", 40, true, null, null, 0}));
        assertThatThrownBy(() -> session.createQuery(User.class)
                .where("name", "=", "Ann").single())
                .isInstanceOf(NonUniqueResultException.class)
                .hasMessageContaining("Expected exactly one User(users) but the query returned 2");
    }

    @Test
    @DisplayName("an unknown column is rejected before any SQL is sent")
    void rejectsUnknownColumn() {
        assertThatThrownBy(() -> session.createQuery(User.class)
                .where("password", "=", "x").list())
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Unknown column 'password'");
        assertThat(database.sql()).isEmpty();
    }

    @Test
    @DisplayName("an operator given as text is validated against the whitelist")
    void rejectsUnknownOperator() {
        assertThatThrownBy(() -> session.createQuery(User.class)
                .where("name", "= 'x' OR 1=1", "x"))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Unsupported operator");
    }

    @Test
    @DisplayName("a value of the wrong type is rejected before any SQL is sent")
    void rejectsWrongValueType() {
        assertThatThrownBy(() -> session.createQuery(User.class)
                .where("age", ">", "eighteen"))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("cannot be compared with column 'age'");
    }

    @Test
    @DisplayName("negative limit or offset is rejected")
    void rejectsNegativePagination() {
        assertThatThrownBy(() -> session.createQuery(User.class).limit(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must not be negative");
        assertThatThrownBy(() -> session.createQuery(User.class).offset(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset must not be negative");
    }

    @Test
    @DisplayName("rows returned by a query are managed instances and are shared with find()")
    void returnsManagedInstances() {
        database.onQuery(SELECT + "WHERE id = ?", 1L, "a@b.c", "Ann", 30, true, null, null, 0);

        User fromQuery = session.createQuery(User.class)
                .where("id", "=", 1L).single();
        User fromFind = session.find(User.class, 1L).orElseThrow();

        assertThat(fromFind).isSameAs(fromQuery);
        assertThat(session.statistics().findsIssued()).isEqualTo(1);
        assertThat(session.statistics().cacheHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("a query flushes pending changes before reading")
    void flushesBeforeReading() {
        database.onQuery(SELECT_USER, 1L, "a@b.c", "Ann", 30, true, null, null, 0);
        database.onQuery(SELECT.trim() + " WHERE active = ?", 1L, "a@b.c", "Anna", 30, true, null, null, 1);
        User user = session.find(User.class, 1L).orElseThrow();

        session.beginTransaction();
        user.setName("Anna");
        List<User> rows = session.createQuery(User.class)
                .where("active", "=", true).list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getName()).isEqualTo("Anna");
        assertThat(database.sql()).containsExactly(SELECT_USER,
                "UPDATE users SET name = ?, version = ? WHERE id = ? AND version = ?",
                SELECT.trim() + " WHERE active = ?");
    }

    @Test
    @DisplayName("a foreign key can be filtered on")
    void filtersOnForeignKey() {
        database.onQuery(SELECT_ORDERS + "WHERE user_id IN (?, ?)",
                1L, 5L, "book", new java.math.BigDecimal("10.00"), 0L);

        List<Order> orders = session.createQuery(Order.class)
                .in("user_id", 5L, 6L).list();

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getDescription()).isEqualTo("book");
        assertThat(database.statements().get(0).parameters()).containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("a query does not work after the session is closed")
    void rejectsQueryAfterClose() {
        session.createQuery(User.class);
        session.close();

        assertThatThrownBy(() -> session.createQuery(User.class))
                .isInstanceOf(io.microorm.exception.PersistenceException.class)
                .hasMessageContaining("Session is closed");
    }

    private static final String SELECT_USER = "SELECT id, email, name, age, active, bio, created_at, "
            + "version FROM users WHERE id = ?";
}