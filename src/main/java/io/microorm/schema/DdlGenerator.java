package io.microorm.schema;

import io.microorm.annotation.GenerationType;
import io.microorm.metadata.AssociationMetadata;
import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import io.microorm.metadata.MetadataRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Builds PostgreSQL DDL from entity metadata.
 *
 * <p>The generator is a pure function from {@link EntityMetadata} to SQL text: it never touches a
 * database. Statements come in three groups, and the order matters because the database resolves
 * foreign keys at {@code ALTER TABLE} time:
 *
 * <ol>
 *   <li>{@code CREATE SEQUENCE} for entities using {@link GenerationType#SEQUENCE}</li>
 *   <li>{@code CREATE TABLE} for every entity</li>
 *   <li>{@code ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY} for many-to-one associations</li>
 * </ol>
 *
 * <p>{@link GenerationType#AUTO} is exported as an identity column, because choosing between a
 * sequence and an identity requires a live {@link java.sql.DatabaseMetaData} lookup, and DDL
 * generation happens before any connection is opened.
 */
public final class DdlGenerator {

    private final MetadataRegistry registry;

    public DdlGenerator(MetadataRegistry registry) {
        this.registry = registry;
    }

    /**
     * Generates the full script for a set of entities, ordered so that it can be executed as is.
     *
     * @param entities entities to export
     * @return DDL statements without trailing semicolons, ready to be printed or written to a file
     */
    public List<String> createSchemaStatements(Collection<EntityMetadata> entities) {
        List<EntityMetadata> ordered = new ArrayList<>(entities);
        List<String> statements = new ArrayList<>();
        ordered.stream()
                .map(this::sequenceName)
                .flatMap(Optional::stream)
                .map(name -> "CREATE SEQUENCE IF NOT EXISTS " + name)
                .forEach(statements::add);
        ordered.stream().map(this::createTableStatement).forEach(statements::add);
        ordered.stream().flatMap(entity -> foreignKeyStatements(entity).stream()).forEach(statements::add);
        return List.copyOf(statements);
    }

    /**
     * Generates {@code CREATE TABLE} for one entity.
     *
     * @param entity entity metadata
     * @return the {@code CREATE TABLE} statement
     */
    public String createTableStatement(EntityMetadata entity) {
        List<String> definitions = new ArrayList<>();
        for (FieldMetadata field : entity.fields()) {
            definitions.add("    " + columnDefinition(entity, field));
        }
        return "CREATE TABLE " + entity.tableName() + " (\n"
                + String.join(",\n", definitions)
                + "\n)";
    }

    /**
     * Generates the sequence name for an entity, when it uses a sequence.
     *
     * @param entity entity metadata
     * @return the sequence name, empty for identity-based identifiers
     */
    public Optional<String> sequenceName(EntityMetadata entity) {
        return entity.identifier().generation()
                .filter(strategy -> strategy == GenerationType.SEQUENCE)
                .map(ignored -> entity.tableName() + "_id_seq");
    }

    /**
     * Generates {@code ALTER TABLE} statements for the associations of one entity.
     *
     * <p>A reference to a table that is not part of the exported set is skipped, because the
     * generator cannot know whether that table is maintained by migrations or by another tool.
     *
     * @param entity entity metadata
     * @return foreign key statements, possibly empty
     */
    public List<String> foreignKeyStatements(EntityMetadata entity) {
        List<String> statements = new ArrayList<>();
        for (FieldMetadata field : entity.fields()) {
            for (AssociationMetadata association : field.association().stream().toList()) {
                resolveTargetTable(association).ifPresent(targetTable -> statements.add(
                        "ALTER TABLE " + entity.tableName()
                                + " ADD CONSTRAINT fk_" + entity.tableName() + "_" + association.joinColumn()
                                + " FOREIGN KEY (" + association.joinColumn() + ")"
                                + " REFERENCES " + targetTable + " (id)"));
            }
        }
        return List.copyOf(statements);
    }

    private Optional<String> resolveTargetTable(AssociationMetadata association) {
        try {
            return Optional.of(registry.metadataFor(association.targetType()).tableName());
        } catch (RuntimeException notAnExportedEntity) {
            return Optional.empty();
        }
    }

    private String columnDefinition(EntityMetadata entity, FieldMetadata field) {
        if (field.identifier()) {
            return identifierDefinition(entity, field);
        }
        StringBuilder column = new StringBuilder(field.column()).append(' ').append(columnType(field));
        if (!field.nullable() || field.version()) {
            column.append(" NOT NULL");
        }
        if (field.version()) {
            // A version column starts at zero, which keeps optimistic locking working even for rows
            // inserted outside of MicroORM.
            column.append(" DEFAULT 0");
        }
        return column.toString();
    }

    /**
     * A many-to-one association is stored as the referenced identifier, so the foreign key column
     * reuses the SQL type of the identifier it points at.
     */
    private String columnType(FieldMetadata field) {
        if (!field.isAssociation()) {
            return field.sqlType();
        }
        AssociationMetadata association = field.association().orElseThrow();
        try {
            return registry.metadataFor(association.targetType()).identifier().sqlType();
        } catch (RuntimeException notAnExportedEntity) {
            return "BIGINT";
        }
    }

    private String identifierDefinition(EntityMetadata entity, FieldMetadata field) {
        if (field.generation().isEmpty()) {
            // An identifier assigned by the application: a plain NOT NULL primary key.
            return field.column() + " " + field.sqlType() + " NOT NULL PRIMARY KEY";
        }
        return switch (field.generation().orElseThrow()) {
            case IDENTITY, AUTO -> field.column() + " " + field.sqlType()
                    + " GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
            case SEQUENCE -> field.column() + " " + field.sqlType() + " NOT NULL PRIMARY KEY DEFAULT nextval('"
                    + entity.tableName() + "_id_seq')";
        };
    }
}