package io.microorm.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.microorm.example.User;
import io.microorm.example.Order;

class PersistenceContextTest {

    private PersistenceContext context;
    private User ann;
    private User bob;

    @BeforeEach
    void setUp() {
        context = new PersistenceContext();
        ann = new User("ann@example.com", "Ann", 30, true);
        ann.setId(1L);
        bob = new User("bob@example.com", "Bob", 40, true);
        bob.setId(2L);
    }

    @Test
    @DisplayName("the same identifier always resolves to the same instance")
    void keepsIdentity() {
        assertThat(context.put(User.class, 1L, ann)).isEmpty();

        assertThat(context.get(User.class, 1L)).contains(ann);
        assertThat(context.get(User.class, 1L)).contains(context.get(User.class, 1L)
                .orElseThrow());
        assertThat(context.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("put reports the instance it replaced, which is how a re-registration is noticed")
    void reportsReplacement() {
        context.put(User.class, 1L, ann);
        User other = new User("x@example.com", "X", 1, false);
        other.setId(1L);

        Optional<Object> replaced = context.put(User.class, 1L, other);

        assertThat(replaced).contains(ann);
        assertThat(context.get(User.class, 1L)).contains(other);
        assertThat(context.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("unknown lookups are empty, not null")
    void returnsEmptyForUnknownRows() {
        assertThat(context.get(User.class, 99L)).isEmpty();
        assertThat(context.get(Order.class, 99L)).isEmpty();
        assertThat(context.remove(User.class, 99L)).isEmpty();
        assertThat(context.entities(User.class)).isEmpty();
        assertThat(context.isEmpty(User.class)).isTrue();
        assertThat(context.size()).isZero();
    }

    @Test
    @DisplayName("entities of one type do not collide with another type using the same identifier")
    void separatesTypes() {
        Order order = new Order("book", new java.math.BigDecimal("10.00"));
        order.setId(1L);

        context.put(User.class, 1L, ann);
        context.put(Order.class, 1L, order);

        assertThat(context.get(User.class, 1L)).contains(ann);
        assertThat(context.get(Order.class, 1L)).contains(order);
        assertThat(context.managedTypes()).containsExactly(User.class, Order.class);
        assertThat(context.entities()).containsExactly(ann, order);
    }

    @Test
    @DisplayName("instances are returned in insertion order so that flushes are reproducible")
    void keepsInsertionOrder() {
        context.put(User.class, 2L, bob);
        context.put(User.class, 1L, ann);

        assertThat(context.entities(User.class)).containsExactly(bob, ann);
    }

    @Test
    @DisplayName("removing a row evicts the instance and drops the type when it was the last one")
    void removes() {
        context.put(User.class, 1L, ann);
        context.put(User.class, 2L, bob);

        assertThat(context.remove(User.class, 1L)).contains(ann);
        assertThat(context.contains(User.class)).isTrue();

        context.remove(User.class, 2L);

        assertThat(context.contains(User.class)).isFalse();
        assertThat(context.size()).isZero();
        assertThat(context.managedTypes()).isEmpty();
    }

    @Test
    @DisplayName("clear drops everything")
    void clears() {
        context.put(User.class, 1L, ann);
        context.put(Order.class, 1L, new Order("x", java.math.BigDecimal.ONE));

        context.clear();

        assertThat(context.size()).isZero();
        assertThat(context.managedTypes()).isEmpty();
        assertThat(context.entities()).isEmpty();
    }
}