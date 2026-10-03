package io.microorm.sql;

import io.microorm.metadata.FieldMetadata;

/**
 * A column together with the value that will be written into it.
 *
 * @param field      the mapped field owning the column
 * @param value      value to write, may be {@code null}
 * @param columnType type actually stored in the column; for an association this is the type of the
 *                   referenced identifier rather than the type of the association field
 */
public record ColumnValue(FieldMetadata field, Object value, Class<?> columnType) {

    /**
     * Creates a value for a scalar column.
     *
     * @param field mapped field
     * @param value value to write
     * @return the column value
     */
    public static ColumnValue of(FieldMetadata field, Object value) {
        return new ColumnValue(field, value, field.javaType());
    }
}
