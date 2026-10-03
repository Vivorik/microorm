package io.microorm.session;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import io.microorm.metadata.MetadataRegistry;
import io.microorm.sql.ColumnValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures and compares the column values of an entity.
 *
 * <p>This is the whole mechanism behind dirty checking: a snapshot is taken when an entity becomes
 * managed, and at flush time the current values are compared against it, so an UPDATE mentions only
 * the columns that really changed.
 *
 * <p>Arrays are copied on capture and compared with {@link Objects#deepEquals}, otherwise an
 * in-place change of a {@code byte[]} column would never be detected.
 */
final class FieldSnapshot {

    private final MetadataRegistry registry;

    FieldSnapshot(MetadataRegistry registry) {
        this.registry = registry;
    }

    /**
     * Captures the current values of the updatable columns and the version.
     *
     * @param entity metadata of the entity
     * @param target managed instance
     * @return values by field, in declaration order
     */
    Map<FieldMetadata, Object> capture(EntityMetadata entity, Object target) {
        Map<FieldMetadata, Object> values = new LinkedHashMap<>();
        for (FieldMetadata field : entity.updatableFields()) {
            values.put(field, copyIfArray(field.getValue(target)));
        }
        entity.version().ifPresent(field -> values.put(field, copyIfArray(field.getValue(target))));
        return values;
    }

    /**
     * Lists the columns whose value differs from the snapshot.
     *
     * @param entity   metadata of the entity
     * @param target   managed instance
     * @param snapshot previously captured values
     * @return the dirty columns, empty when nothing changed
     */
    List<ColumnValue> changedColumns(EntityMetadata entity, Object target, Map<FieldMetadata, Object> snapshot) {
        List<ColumnValue> changed = new ArrayList<>();
        for (FieldMetadata field : entity.updatableFields()) {
            Object before = snapshot.get(field);
            if (!Objects.deepEquals(before, field.getValue(target))) {
                changed.add(columnValue(field, target));
            }
        }
        return changed;
    }

    /**
     * @param field  field to read
     * @param target owning instance
     * @return the value written to the column; for an association this is the referenced identifier
     */
    ColumnValue columnValue(FieldMetadata field, Object target) {
        Object value = field.getValue(target);
        if (!field.isAssociation()) {
            return ColumnValue.of(field, value);
        }
        if (value == null) {
            return new ColumnValue(field, null, columnTypeOf(field));
        }
        // A lazy proxy answers the identifier field without loading, so reading the foreign key here
        // never triggers a SELECT.
        EntityMetadata targetMetadata = registry.metadataFor(field.association().orElseThrow().targetType());
        return new ColumnValue(field, targetMetadata.identifier().getValue(value),
                targetMetadata.identifier().javaType());
    }

    /**
     * @param field association field
     * @return SQL type of the foreign key column
     */
    Class<?> columnTypeOf(FieldMetadata field) {
        return registry.columnJavaType(field);
    }

    /**
     * @param version  loaded version value
     * @param javaType type of the version column
     * @return the value to store, used by optimistic locking
     */
    static Object increment(Object version, Class<?> javaType) {
        // NOTE: an if/else instead of a ternary on purpose - a conditional expression over int and long
        // branches is promoted to long, which would box every version as a Long.
        return addOne((Number) version, javaType);
    }

    /**
     * @param javaType type of the version column
     * @return the value a new row starts with: {@code 0}, boxed as the column type
     */
    static Object initialVersion(Class<?> javaType) {
        return javaType == Integer.class ? Integer.valueOf(0) : Long.valueOf(0L);
    }

    private static Object addOne(Number value, Class<?> javaType) {
        if (javaType == Integer.class) {
            return Integer.valueOf(value.intValue() + 1);
        }
        return Long.valueOf(value.longValue() + 1L);
    }

    private Object copyIfArray(Object value) {
        return value instanceof byte[] bytes ? bytes.clone() : value;
    }
}
