package io.microorm.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import io.microorm.metadata.MetadataRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.microorm.example.User;
import io.microorm.example.Order;

class SqlGeneratorTest {

    private final MetadataRegistry registry = new MetadataRegistry();
    private final SqlGenerator generator = new SqlGenerator(new PostgresDialect());

    private EntityMetadata user() {
        return registry.metadataFor(User.class);
    }

    private EntityMetadata order() {
        return registry.metadataFor(Order.class);
    }

    private ColumnValue value(EntityMetadata entity, String column, Object value) {
        FieldMetadata field = entity.fieldByColumn(column).orElseThrow();
        return ColumnValue.of(field, value);
    }

    @Test
    @DisplayName("INSERT lists every insertable column with placeholders and returns the generated id")
    void generatesInsert() {
        EntityMetadata entity = user();
        List<ColumnValue> columns = List.of(
                value(entity, "email", "a@b.c"),
                value(entity, "name", "Ann"),
                value(entity, "age", 30));

        InsertStatement statement = generator.insert(entity, columns);

        assertThat(statement.sql()).isEqualTo("INSERT INTO users (email, name, age) VALUES (?, ?, ?)"
                + " RETURNING id");
        assertThat(statement.parameters()).containsExactly(
                new Bind("a@b.c", String.class),
                new Bind("Ann", String.class),
                new Bind(30, Integer.class));
        assertThat(statement.returnsGeneratedKeys()).isTrue();
        assertThat(statement.returningColumn()).contains("id");
        assertThat(statement.parameterCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("INSERT without RETURNING when the identifier is bound explicitly")
    void generatesInsertWithExplicitId() {
        EntityMetadata entity = user();
        List<ColumnValue> columns = List.of(value(entity, "id", 7L), value(entity, "email", "a@b.c"));

        InsertStatement statement = generator.insert(entity, columns);

        assertThat(statement.sql()).isEqualTo("INSERT INTO users (id, email) VALUES (?, ?)");
        assertThat(statement.returnsGeneratedKeys()).isFalse();
    }

    @Test
    @DisplayName("UPDATE mentions only the dirty columns")
    void generatesUpdate() {
        EntityMetadata entity = user();

        UpdateStatement statement = generator.update(entity,
                List.of(value(entity, "name", "Ann")), 7L, Optional.empty());

        assertThat(statement.sql()).isEqualTo("UPDATE users SET name = ? WHERE id = ?");
        assertThat(statement.parameters()).containsExactly(new Bind("Ann", String.class), new Bind(7L, Long.class));
        assertThat(statement.versionGuarded()).isFalse();
    }

    @Test
    @DisplayName("UPDATE adds the version to WHERE when the entity has @Version")
    void generatesVersionGuardedUpdate() {
        EntityMetadata entity = user();

        UpdateStatement statement = generator.update(entity,
                List.of(value(entity, "name", "Ann"), value(entity, "version", 4)), 7L, Optional.of(3));

        assertThat(statement.sql())
                .isEqualTo("UPDATE users SET name = ?, version = ? WHERE id = ? AND version = ?");
        assertThat(statement.parameters()).containsExactly(
                new Bind("Ann", String.class),
                new Bind(4, Integer.class),
                new Bind(7L, Long.class),
                new Bind(3, Integer.class));
        assertThat(statement.versionGuarded()).isTrue();
    }

    @Test
    @DisplayName("an UPDATE without changed columns is rejected instead of producing invalid SQL")
    void rejectsEmptyUpdate() {
        EntityMetadata entity = user();

        assertThatThrownBy(() -> generator.update(entity, List.of(), 7L, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no changed columns");
    }

    @Test
    @DisplayName("DELETE uses the identifier and, when present, the version")
    void generatesDelete() {
        DeleteStatement plain = generator.delete(user(), 7L, Optional.empty());

        assertThat(plain.sql()).isEqualTo("DELETE FROM users WHERE id = ?");
        assertThat(plain.parameters()).containsExactly(new Bind(7L, Long.class));

        DeleteStatement guarded = generator.delete(user(), 7L, Optional.of(2));

        assertThat(guarded.sql()).isEqualTo("DELETE FROM users WHERE id = ? AND version = ?");
        assertThat(guarded.parameters()).containsExactly(new Bind(7L, Long.class), new Bind(2, Integer.class));
        assertThat(guarded.versionGuarded()).isTrue();
    }

    @Test
    @DisplayName("SELECT statements project the mapped columns only")
    void generatesSelects() {
        SelectStatement byId = generator.selectById(user(), 7L);
        SelectStatement all = generator.selectAll(user());

        assertThat(byId.sql()).isEqualTo("SELECT id, email, name, age, active, bio, created_at, version "
                + "FROM users WHERE id = ?");
        assertThat(byId.parameters()).containsExactly(new Bind(7L, Long.class));
        assertThat(byId.entity()).isSameAs(user());
        assertThat(all.sql()).isEqualTo("SELECT id, email, name, age, active, bio, created_at, version "
                + "FROM users");
        assertThat(all.parameters()).isEmpty();
    }

    @Test
    @DisplayName("SELECT with a clause keeps parameters in placeholder order")
    void generatesClauseSelect() {
        SelectStatement statement = generator.select(order(), "WHERE age > ? ORDER BY name ASC",
                List.of(new Bind(18, Integer.class)));

        assertThat(statement.sql()).startsWith("SELECT id, user_id, description, amount, version FROM orders ");
        assertThat(statement.sql()).endsWith("WHERE age > ? ORDER BY name ASC");
        assertThat(statement.describe()).contains("Bind[value=18");
    }

    @Test
    @DisplayName("an aggregate ignores the projection but keeps the clause")
    void generatesAggregate() {
        assertThat(generator.aggregate(user(), "")).isEqualTo("SELECT count(*) FROM users");
        assertThat(generator.aggregate(user(), "WHERE active = ?"))
                .isEqualTo("SELECT count(*) FROM users WHERE active = ?");
    }

    @Test
    @DisplayName("association columns are bound with the type of the referenced identifier")
    void bindsForeignKeyWithReferencedType() {
        EntityMetadata entity = order();
        FieldMetadata userField = entity.fieldByColumn("user_id").orElseThrow();
        ColumnValue foreignKey = new ColumnValue(userField, 7L, Long.class);

        InsertStatement statement = generator.insert(entity, List.of(foreignKey));

        assertThat(statement.sql()).contains("user_id");
        assertThat(statement.parameters()).containsExactly(new Bind(7L, Long.class));
    }

    @Test
    @DisplayName("null values travel as bind parameters, never as SQL text")
    void bindsNullAsParameter() {
        EntityMetadata entity = user();

        InsertStatement statement = generator.insert(entity, List.of(value(entity, "bio", null)));

        assertThat(statement.sql()).isEqualTo("INSERT INTO users (bio) VALUES (?) RETURNING id");
        assertThat(statement.parameters()).containsExactly(new Bind(null, String.class));
        assertThat(statement.parameters().get(0).isNull()).isTrue();
    }

    @Test
    @DisplayName("the sequence expression validates the sequence name before concatenating it")
    void generatesNextval() {
        assertThat(generator.nextSequenceValue("orders_id_seq")).isEqualTo("SELECT nextval('orders_id_seq')");
        assertThatThrownBy(() -> generator.nextSequenceValue("orders'; DROP TABLE users; --"))
                .isInstanceOf(io.microorm.exception.MappingException.class)
                .hasMessageContaining("Invalid SQL identifier");
    }
}