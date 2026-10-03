package io.microorm.metadata;

import io.microorm.annotation.GenerationType;
import io.microorm.exception.MappingException;
import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Immutable description of one mapped field: its Java name, its column, its SQL type and the flags
 * that drive INSERT/UPDATE generation.
 *
 * <p>Every mapped field owns exactly one column. For an association that column is the foreign key,
 * which keeps the row-to-column mapping one-to-one and therefore keeps the generated SQL trivial.
 *
 * @param name             field name in the entity class
 * @param column           physical column name
 * @param javaType         boxed type of the value, never a primitive
 * @param field            reflection handle, already made accessible
 * @param identifier       whether this field is the primary key
 * @param version          whether this field is the optimistic locking version
 * @param generation       database-side identifier generation strategy, empty for assigned ids
 * @param association      association description, empty for plain columns
 * @param nullable         whether {@code NULL} is allowed
 * @param insertable       whether the value is written on INSERT
 * @param updatable        whether the value is written on UPDATE
 * @param columnDefinition explicit SQL type used by DDL generation, empty to derive it from the Java type
 */
public record FieldMetadata(
        String name,
        String column,
        Class<?> javaType,
        Field field,
        boolean identifier,
        boolean version,
        Optional<GenerationType> generation,
        Optional<AssociationMetadata> association,
        boolean nullable,
        boolean insertable,
        boolean updatable,
        String columnDefinition) {

    public FieldMetadata {
        field.setAccessible(true);
    }

    /** @return {@code true} when the field stores a foreign key instead of a scalar */
    public boolean isAssociation() {
        return association.isPresent();
    }

    /** @return SQL type used by DDL generation for this column */
    public String sqlType() {
        return columnDefinition.isBlank()
                ? SqlTypeResolver.resolveColumnType(javaType)
                : columnDefinition;
    }

    /**
     * Reads the value of this field from an entity instance.
     *
     * <p>The reflection handle was made accessible in the canonical constructor, so the common path
     * is a plain {@link Field#get}; failures can only mean "field is not part of that object".
     *
     * @param entity instance to read from
     * @return field value, boxed
     * @throws MappingException when the field does not belong to the given instance
     */
    public Object getValue(Object entity) {
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new MappingException("Cannot read field " + field, e);
        }
    }

    /**
     * Writes the value of this field into an entity instance.
     *
     * @param entity instance to modify
     * @param value  new value, boxed
     * @throws MappingException when the field does not belong to the given instance or the value has
     *                          the wrong type
     */
    public void setValue(Object entity, Object value) {
        try {
            field.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new MappingException("Cannot write field " + field, e);
        } catch (IllegalArgumentException e) {
            throw new MappingException("Cannot assign " + value + " to field " + field, e);
        }
    }
}