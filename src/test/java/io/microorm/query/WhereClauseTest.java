package io.microorm.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.MappingException;
import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.MetadataRegistry;
import io.microorm.sql.Bind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.microorm.example.User;
import io.microorm.example.Order;

class WhereClauseTest {

    private final MetadataRegistry registry = new MetadataRegistry();
    private final EntityMetadata user = registry.metadataFor(User.class);
    private final WhereClause clause = new WhereClause(user, registry);

    @Test
    @DisplayName("an empty clause renders nothing")
    void emptyClause() {
        assertThat(clause.clause()).isEmpty();
        assertThat(clause.parameters()).isEmpty();
        assertThat(clause.size()).isZero();
        assertThat(clause.predicates()).isEmpty();
    }

    @Test
    @DisplayName("conditions are joined with the operator of the condition itself")
    void rendersConditions() {
        clause.add(LogicalOperator.AND, "age", ComparisonOperator.GREATER, List.of(18))
                .add(LogicalOperator.AND, "active", ComparisonOperator.EQUAL, List.of(true))
                .add(LogicalOperator.OR, "name", ComparisonOperator.EQUAL, List.of("Ann"));

        assertThat(clause.clause()).isEqualTo("WHERE age > ? AND active = ? OR name = ?");
        assertThat(clause.parameters()).containsExactly(
                new Bind(18, Integer.class),
                new Bind(true, Boolean.class),
                new Bind("Ann", String.class));
        assertThat(clause.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("IN renders one placeholder per value, in order")
    void rendersIn() {
        clause.addIn(LogicalOperator.AND, "name", List.of("a", "b", "c"));

        assertThat(clause.clause()).isEqualTo("WHERE name IN (?, ?, ?)");
        assertThat(clause.parameters()).extracting(Bind::value).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("NOT IN is rendered too")
    void rendersNotIn() {
        clause.addNotIn(LogicalOperator.AND, "id", List.of(1L, 2L));

        assertThat(clause.clause()).isEqualTo("WHERE id NOT IN (?, ?)");
    }

    @Test
    @DisplayName("unary operators take no placeholder")
    void rendersUnaryOperators() {
        clause.add(LogicalOperator.AND, "bio", ComparisonOperator.IS_NULL, List.of())
                .add(LogicalOperator.AND, "name", ComparisonOperator.IS_NOT_NULL, List.of());

        assertThat(clause.clause()).isEqualTo("WHERE bio IS NULL AND name IS NOT NULL");
        assertThat(clause.parameters()).isEmpty();
    }

    @Test
    @DisplayName("foreign key columns can be compared as well")
    void validatesForeignKeyColumn() {
        EntityMetadata order = registry.metadataFor(Order.class);
        WhereClause orderClause = new WhereClause(order, registry);

        orderClause.addIn(LogicalOperator.AND, "user_id", List.of(1L, 2L));

        assertThat(orderClause.clause()).isEqualTo("WHERE user_id IN (?, ?)");
    }

    @Test
    @DisplayName("an unknown column is rejected and the message lists the valid ones")
    void rejectsUnknownColumn() {
        assertThatThrownBy(() -> clause.add(LogicalOperator.AND, "nope", ComparisonOperator.EQUAL, List.of(1)))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Unknown column 'nope' for User(users)")
                .hasMessageContaining("known columns: id, email, name");
    }

    @Test
    @DisplayName("a column name that could break out of the SQL text is rejected")
    void rejectsInjectedColumn() {
        assertThatThrownBy(() -> clause.add(LogicalOperator.AND, "1=1; DROP TABLE users",
                ComparisonOperator.EQUAL, List.of(1)))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Unknown column");
    }

    @Test
    @DisplayName("a value of the wrong type is rejected before it reaches the database")
    void rejectsWrongValueType() {
        assertThatThrownBy(() -> clause.add(LogicalOperator.AND, "age",
                ComparisonOperator.GREATER, List.of("eighteen")))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("cannot be compared with column 'age'");
    }

    @Test
    @DisplayName("a number is widened to the column type")
    void widensNumbers() {
        EntityMetadata order = registry.metadataFor(Order.class);
        WhereClause orderClause = new WhereClause(order, registry);

        orderClause.addIn(LogicalOperator.AND, "user_id", List.of(1, 2));

        assertThat(orderClause.parameters()).extracting(Bind::value).containsExactly(1L, 2L);
        assertThat(orderClause.parameters()).extracting(Bind::javaType).containsOnly(Long.class);
    }

    @Test
    @DisplayName("a unary operator with a value, and a value-less operator without one, are rejected")
    void rejectsInconsistentArguments() {
        assertThatThrownBy(() -> clause.add(LogicalOperator.AND, "bio", ComparisonOperator.IS_NULL,
                List.of("x")))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("must not have a value");
        assertThatThrownBy(() -> clause.add(LogicalOperator.AND, "age", ComparisonOperator.GREATER, List.of()))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("needs a value");
        assertThatThrownBy(() -> new Predicate(LogicalOperator.AND, user.fieldByColumn("age").orElseThrow(),
                ComparisonOperator.GREATER, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs at least one value");
    }

    @Test
    @DisplayName("LIKE accepts a string, and a string column accepts anything")
    void allowsTextComparisons() {
        clause.add(LogicalOperator.AND, "email", ComparisonOperator.LIKE, List.of("%@example.com"))
                .add(LogicalOperator.AND, "name", ComparisonOperator.EQUAL, List.of(42));

        assertThat(clause.clause()).isEqualTo("WHERE email LIKE ? AND name = ?");
    }

    @Test
    @DisplayName("a null value is accepted for a nullable column")
    void allowsNullValues() {
        clause.add(LogicalOperator.AND, "bio", ComparisonOperator.EQUAL, java.util.Collections.singletonList(null));

        assertThat(clause.parameters()).containsExactly(new Bind(null, String.class));
    }
}