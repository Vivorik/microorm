package io.microorm.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.microorm.support.TestEntities;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PersistenceContextTest {

    private PersistenceContext context;
    private TestEntities.User ann;
    private TestEntities.User bob;

    @BeforeEach
    void setUp() {
        context = new PersistenceContext();
        ann = new TestEntities.User("ann@example.com", "Ann", 30, true);
        ann.setId(1L);
        bob = new TestEntities.User("bob@example.com", "Bob", 40, true);
        bob.setId(2L);
    }

    @Test
    @DisplayName("the same identifier always resolves to the same instance")
    void keepsIdentity() {
        assertThat(context.put(TestEntities.User.class, 1L, ann)).isEmpty();

        assertThat(context.get(TestEntities.User.class, 1L)).contains(ann);
        assertThat(context.get(TestEntities.User.class, 1L)).contains(context.get(TestEntities.User.class, 1L)
                .orElseThrow());
        assertThat(context.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("put reports the instance it replaced, which is how a re-registration is noticed")
    void reportsReplacement() {
        context.put(TestEntities.User.class, 1L, ann);
        TestEntities.User other = new TestEntities.User("x@example.com", "X", 1, false);
        other.setId(1L);

        Optional<Object> replaced = context.put(TestEntities.User.class, 1L, other);

        assertThat(replaced).contains(ann);
        assertThat(context.get(TestEntities.User.class, 1L)).contains(other);
        assertThat(context.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("unknown lookups are empty, not null")
    void returnsEmptyForUnknownRows() {
        assertThat(context.get(TestEntities.User.class, 99L)).isEmpty();
        assertThat(context.get(TestEntities.Order.class, 99L)).isEmpty();
        assertThat(context.remove(TestEntities.User.class, 99L)).isEmpty();
        assertThat(context.entities(TestEntities.User.class)).isEmpty();
        assertThat(context.isEmpty(TestEntities.User.class)).isTrue();
        assertThat(context.size()).isZero();
    }

    @Test
    @DisplayName("entities of one type do not collide with another type using the same identifier")
    void separatesTypes() {
        TestEntities.Order order = new TestEntities.Order("book", new java.math.BigDecimal("10.00"));
        order.setId(1L);

        context.put(TestEntities.User.class, 1L, ann);
        context.put(TestEntities.Order.class, 1L, order);

        assertThat(context.get(TestEntities.User.class, 1L)).contains(ann);
        assertThat(context.get(TestEntities.Order.class, 1L)).contains(order);
        assertThat(context.managedTypes()).containsExactly(TestEntities.User.class, TestEntities.Order.class);
        assertThat(context.entities()).containsExactly(ann, order);
    }

    @Test
    @DisplayName("instances are returned in insertion order so that flushes are reproducible")
    void keepsInsertionOrder() {
        context.put(TestEntities.User.class, 2L, bob);
        context.put(TestEntities.User.class, 1L, ann);

        assertThat(context.entities(TestEntities.User.class)).containsExactly(bob, ann);
    }

    @Test
    @DisplayName("removing a row evicts the instance and drops the type when it was the last one")
    void removes() {
        context.put(TestEntities.User.class, 1L, ann);
        context.put(TestEntities.User.class, 2L, bob);

        assertThat(context.remove(TestEntities.User.class, 1L)).contains(ann);
        assertThat(context.contains(TestEntities.User.class)).isTrue();

        context.remove(TestEntities.User.class, 2L);

        assertThat(context.contains(TestEntities.User.class)).isFalse();
        assertThat(context.size()).isZero();
        assertThat(context.managedTypes()).isEmpty();
    }

    @Test
    @DisplayName("clear drops everything")
    void clears() {
        context.put(TestEntities.User.class, 1L, ann);
        context.put(TestEntities.Order.class, 1L, new TestEntities.Order("x", java.math.BigDecimal.ONE));

        context.clear();

        assertThat(context.size()).isZero();
        assertThat(context.managedTypes()).isEmpty();
        assertThat(context.entities()).isEmpty();
    }
}