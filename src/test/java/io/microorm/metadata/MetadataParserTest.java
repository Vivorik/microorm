package io.microorm.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.annotation.Entity;
import io.microorm.annotation.GenerationType;
import io.microorm.annotation.Id;
import io.microorm.exception.MappingException;
import io.microorm.support.TestEntities;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetadataParserTest {

    private final MetadataParser parser = new MetadataParser();

    @Test
    @DisplayName("maps annotations of a typical entity to table, columns and flags")
    void mapsTypicalEntity() {
        EntityMetadata metadata = parser.parse(TestEntities.User.class);

        assertThat(metadata.type()).isEqualTo(TestEntities.User.class);
        assertThat(metadata.tableName()).isEqualTo("users");
        assertThat(metadata.identifier().column()).isEqualTo("id");
        assertThat(metadata.identifier().generation()).contains(GenerationType.AUTO);
        assertThat(metadata.version()).map(FieldMetadata::column).contains("version");
        assertThat(metadata.version().orElseThrow().javaType()).isEqualTo(Integer.class);
        assertThat(metadata.selectColumns())
                .isEqualTo("id, email, name, age, active, bio, created_at, version");
        assertThat(metadata.fieldByColumn("email")).map(FieldMetadata::name).contains("email");
        assertThat(metadata.fieldByColumn("nope")).isEmpty();
    }

    @Test
    @DisplayName("columnDefinition wins over the derived SQL type")
    void resolvesColumnDefinitions() {
        EntityMetadata metadata = parser.parse(TestEntities.User.class);

        assertThat(metadata.fieldByColumn("bio").orElseThrow().sqlType()).isEqualTo("TEXT");
        assertThat(metadata.fieldByColumn("name").orElseThrow().sqlType()).isEqualTo("VARCHAR(255)");
        assertThat(metadata.fieldByColumn("created_at").orElseThrow().sqlType())
                .isEqualTo(SqlTypeResolver.resolveColumnType(LocalDateTime.class));
    }

    @Test
    @DisplayName("columns excluded from INSERT and UPDATE are filtered out of the statement lists")
    void splitsInsertableAndUpdatableFields() {
        EntityMetadata metadata = parser.parse(TestEntities.User.class);

        assertThat(metadata.insertableFields()).map(FieldMetadata::column)
                .containsExactly("email", "name", "age", "active", "bio", "version");
        assertThat(metadata.updatableFields()).map(FieldMetadata::column)
                .containsExactly("email", "name", "age", "active", "bio");
        assertThat(metadata.hasGeneratedIdentifier()).isTrue();
    }

    @Test
    @DisplayName("associations are mapped to a foreign key column and keep their target type")
    void mapsAssociation() {
        EntityMetadata metadata = parser.parse(TestEntities.Order.class);

        FieldMetadata user = metadata.fieldByColumn("user_id").orElseThrow();
        assertThat(user.isAssociation()).isTrue();
        AssociationMetadata association = user.association().orElseThrow();
        assertThat(association.type()).isEqualTo(AssociationType.MANY_TO_ONE);
        assertThat(association.targetType()).isEqualTo(TestEntities.User.class);
        assertThat(association.lazy()).isTrue();
        assertThat(association.joinColumn()).isEqualTo("user_id");
        assertThat(metadata.basicFields()).map(FieldMetadata::column)
                .containsExactly("id", "description", "amount", "version");
        // A generated identifier must not appear in the INSERT column list.
        assertThat(metadata.insertableFields()).map(FieldMetadata::column)
                .containsExactly("user_id", "description", "amount", "version");
    }

    @Test
    @DisplayName("table name comes from @Table, otherwise @Entity, otherwise snake_case of the class name")
    void resolvesTableNames() {
        assertThat(parser.parse(TestEntities.AuditEvent.class).tableName()).isEqualTo("audit_events");
        assertThat(parser.parse(TestEntities.BothTableAnnotations.class).tableName()).isEqualTo("wins");
        assertThat(parser.parse(TestEntities.OrderLineItem.class).tableName()).isEqualTo("order_line_item");
    }

    @Test
    @DisplayName("field values can be read and written through metadata")
    void readsAndWritesFields() {
        EntityMetadata metadata = parser.parse(TestEntities.Order.class);
        TestEntities.Order order = new TestEntities.Order("first", new BigDecimal("10.50"));

        metadata.fieldByColumn("description").orElseThrow().setValue(order, "second");
        metadata.fieldByColumn("amount").orElseThrow().setValue(order, new BigDecimal("1.00"));
        metadata.identifier().setValue(order, 42L);

        assertThat(metadata.fieldByColumn("description").orElseThrow().getValue(order)).isEqualTo("second");
        assertThat(order.getAmount()).isEqualByComparingTo("1.00");
        assertThat(metadata.identifier().getValue(order)).isEqualTo(42L);
    }

    @Test
    @DisplayName("newInstance uses the no-argument constructor")
    void createsInstances() {
        EntityMetadata metadata = parser.parse(TestEntities.User.class);

        assertThat(metadata.newInstance()).isInstanceOf(TestEntities.User.class);
    }

    @Test
    @DisplayName("rejects entities without an identifier")
    void rejectsMissingIdentifier() {
        assertThatThrownBy(() -> parser.parse(TestEntities.NoId.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("no @Id");
    }

    @Test
    @DisplayName("rejects two identifiers in one entity")
    void rejectsTwoIdentifiers() {
        assertThatThrownBy(() -> parser.parse(TestEntities.TwoIds.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("@Id more than once");
    }

    @Test
    @DisplayName("column names default to snake_case, which is what the identifier validator accepts")
    void defaultsColumnNamesToSnakeCase() {
        EntityMetadata metadata = parser.parse(TestEntities.CamelCaseColumns.class);

        assertThat(metadata.fieldByColumn("first_name")).map(FieldMetadata::name).contains("firstName");
        assertThat(metadata.selectColumns()).isEqualTo("id, first_name, last_name");
    }

    @Test
    @DisplayName("rejects identifier types other than Long, Integer and UUID")
    void rejectsUnsupportedIdentifierType() {
        assertThatThrownBy(() -> parser.parse(TestEntities.BadIdType.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("@Id of io.microorm.support.TestEntities$BadIdType must be one of");
    }

    @Test
    @DisplayName("rejects @GeneratedValue on a field that is not the identifier")
    void rejectsGeneratedValueOnNonIdentifier() {
        assertThatThrownBy(() -> parser.parse(TestEntities.GeneratedNonId.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("@GeneratedValue is only allowed on @Id");
    }

    @Test
    @DisplayName("rejects field types that are neither basic nor annotated as association")
    void rejectsUnsupportedFieldType() {
        assertThatThrownBy(() -> parser.parse(TestEntities.UnsupportedType.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("java.util.List is not a basic type");
    }

    @Test
    @DisplayName("rejects a final class that needs a lazy proxy subclass")
    void rejectsFinalEntityWithLazyAssociation() {
        assertThatThrownBy(() -> parser.parse(TestEntities.FinalWithLazyAssociation.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("must not be final");
    }

    @Test
    @DisplayName("fails fast on @OneToMany with a pointer to the documentation")
    void rejectsOneToMany() {
        assertThatThrownBy(() -> parser.parse(TestEntities.WithChildren.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@OneToMany on")
                .hasMessageContaining("not implemented");
    }

    @Test
    @DisplayName("rejects entities without a no-argument constructor")
    void rejectsMissingDefaultConstructor() {
        assertThatThrownBy(() -> parser.parse(TestEntities.NoDefaultConstructor.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("no-argument constructor");
    }

    @Test
    @DisplayName("rejects classes that are not annotated with @Entity")
    void rejectsNonEntity() {
        assertThatThrownBy(() -> parser.parse(TestEntities.NotAnEntity.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("not annotated with @Entity");
    }

    @Test
    @DisplayName("rejects identifiers that could break out of the SQL text")
    void rejectsInvalidIdentifiers() {
        assertThatThrownBy(() -> parser.parse(OrderLine.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Invalid SQL identifier");
    }

    @Test
    @DisplayName("counts real parses so that caching can be verified")
    void countsParses() {
        assertThat(parser.parsedEntityCount()).isZero();
        parser.parse(TestEntities.User.class);
        parser.parse(TestEntities.User.class);

        assertThat(parser.parsedEntityCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("generate strategy is exposed as an Optional")
    void exposesGenerationAsOptional() {
        EntityMetadata metadata = parser.parse(TestEntities.Order.class);

        assertThat(metadata.identifier().generation()).isEqualTo(Optional.of(GenerationType.SEQUENCE));
        assertThat(parser.parse(TestEntities.AuditEvent.class).identifier().generation()).isEmpty();
        assertThat(metadata.hasGeneratedIdentifier()).isTrue();
        assertThat(parser.parse(TestEntities.AuditEvent.class).hasGeneratedIdentifier()).isFalse();
    }

    @Entity
    static class OrderLine {

        @Id
        private Long id;

        @io.microorm.annotation.Column(name = "drop table users; --")
        private String hacked;

        OrderLine() {
        }
    }
}