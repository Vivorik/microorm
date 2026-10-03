package io.microorm.sql;

/**
 * A value to be sent to the database as a bind parameter.
 *
 * <p>Everything MicroORM sends to PostgreSQL travels as a {@code PreparedStatement} parameter; no
 * value is ever concatenated into SQL text. {@link #javaType()} is the type of the value that is
 * actually stored in the column, which for a foreign key is the type of the referenced identifier
 * rather than the type of the association field.
 *
 * @param value    the value, may be {@code null}
 * @param javaType basic type of the value, used to pick {@code setObject} versus {@code setNull}
 */
public record Bind(Object value, Class<?> javaType) {

    /** @return {@code true} when the bound value is {@code null} */
    public boolean isNull() {
        return value == null;
    }
}
