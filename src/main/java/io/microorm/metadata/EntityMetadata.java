package io.microorm.metadata;

import io.microorm.exception.MappingException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Immutable metadata of one entity class: table name, identifier, version and the field lists that
 * the SQL generator needs.
 *
 * <p>The field lists are derived once at construction time instead of being filtered on every query,
 * because INSERT/UPDATE generation happens for every dirty entity on every flush.
 *
 * @param type            entity class
 * @param tableName       physical table name
 * @param constructor     accessible no-argument constructor
 * @param identifier      the {@code @Id} field
 * @param version         the {@code @Version} field, empty when the entity has none
 * @param fields          all mapped fields in declaration order
 * @param insertableFields fields written on INSERT (generated identifiers excluded)
 * @param updatableFields  fields written on UPDATE (identifiers and the version excluded)
 * @param basicFields      scalar fields, i.e. everything that is not an association
 */
public record EntityMetadata(
        Class<?> type,
        String tableName,
        Constructor<?> constructor,
        FieldMetadata identifier,
        Optional<FieldMetadata> version,
        List<FieldMetadata> fields,
        List<FieldMetadata> insertableFields,
        List<FieldMetadata> updatableFields,
        List<FieldMetadata> basicFields) {

    /**
     * Creates metadata and derives the per-statement field lists.
     *
     * @param type        entity class
     * @param tableName   physical table name
     * @param constructor accessible no-argument constructor
     * @param identifier  the {@code @Id} field
     * @param version     the {@code @Version} field, may be {@code null}
     * @param fields      all mapped fields
     * @return fully derived metadata
     */
    public static EntityMetadata of(
            Class<?> type,
            String tableName,
            Constructor<?> constructor,
            FieldMetadata identifier,
            Optional<FieldMetadata> version,
            List<FieldMetadata> fields) {

        List<FieldMetadata> all = List.copyOf(fields);
        List<FieldMetadata> insertable = all.stream()
                .filter(f -> f.insertable() && !isDatabaseGenerated(f))
                .toList();
        List<FieldMetadata> updatable = all.stream()
                .filter(f -> f.updatable() && !f.identifier() && !f.version())
                .toList();
        List<FieldMetadata> basic = all.stream()
                .filter(f -> !f.isAssociation())
                .toList();
        return new EntityMetadata(type, tableName, constructor, identifier,
                version == null ? Optional.empty() : version, all, insertable, updatable, basic);
    }

    public EntityMetadata {
        fields = List.copyOf(fields);
        insertableFields = List.copyOf(insertableFields);
        updatableFields = List.copyOf(updatableFields);
        basicFields = List.copyOf(basicFields);
    }

    /** @return {@code true} when the identifier is filled in by the database */
    public boolean hasGeneratedIdentifier() {
        return identifier.generation().isPresent();
    }

    /**
     * Creates a new empty instance of the entity.
     *
     * @return fresh instance created through the no-argument constructor
     * @throws MappingException when instantiation fails
     */
    public Object newInstance() {
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new MappingException("Cannot instantiate " + type.getName()
                    + "; a usable no-argument constructor is required", e);
        }
    }

    /**
     * @return comma separated list of every column, used as the SELECT projection
     */
    public String selectColumns() {
        return fields.stream().map(FieldMetadata::column).collect(Collectors.joining(", "));
    }

    /**
     * Looks a mapped field up by column name.
     *
     * @param column column name as used in SQL
     * @return the field, or empty when this entity has no such column
     */
    public Optional<FieldMetadata> fieldByColumn(String column) {
        return fields.stream().filter(f -> f.column().equals(column)).findFirst();
    }

    /**
     * @return human readable name used in log and exception messages
     */
    public String describe() {
        return type.getSimpleName() + "(" + tableName + ")";
    }

    private static boolean isDatabaseGenerated(FieldMetadata field) {
        return field.identifier() && field.generation().isPresent();
    }

    static String unqualifiedName(Class<?> type) {
        return type.getSimpleName().toLowerCase(Locale.ROOT);
    }
}