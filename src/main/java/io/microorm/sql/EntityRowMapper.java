package io.microorm.sql;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Turns a {@link ResultSet} row into an entity instance.
 *
 * <p>Columns are read by position, in the order {@link EntityMetadata#selectColumns()} produced
 * them, so no name lookup happens per row: {@code getObject(String)} resolves the label case
 * insensitively on every call, which is measurable in a hot loop.
 */
public final class EntityRowMapper {

    private EntityRowMapper() {
        throw new AssertionError("No instances of EntityRowMapper");
    }

    /**
     * Materialises one row.
     *
     * <p>Association columns are read as raw foreign key values; turning them into entities or lazy
     * proxies is the session's job, which keeps this class free of any mapping policy.
     *
     * @param entity   metadata describing the entity
     * @param row      positioned result set
     * @param factories resolver for association values, may return {@code null} for a null column
     * @return a populated entity instance
     * @throws SQLException when the driver cannot read a column
     */
    public static Object map(EntityMetadata entity, ResultSet row, AssociationResolver factories)
            throws SQLException {
        Object instance = entity.newInstance();
        var fields = entity.fields();
        for (int i = 0; i < fields.size(); i++) {
            FieldMetadata field = fields.get(i);
            if (field.isAssociation()) {
                Object foreignKey = read(row, i + 1, factories.columnTypeOf(field));
                factories.resolve(field, foreignKey, instance);
            } else {
                field.setValue(instance, read(row, i + 1, field.javaType()));
            }
        }
        return instance;
    }

    /**
     * Reads one column into a value of the requested type.
     *
     * <p>The column is fetched without a target type and normalised here, because a schema and an entity
     * do not have to agree on the width of a number: a {@code Long} field is perfectly valid for an
     * {@code INTEGER} column, and asking the driver to convert {@code int4} to {@code Long} fails with
     * "conversion not supported". Reading by position is also cheaper than {@code getObject(int, Class)},
     * which makes the driver perform a conversion per column.
     *
     * @param row      positioned result set
     * @param position one-based column index
     * @param javaType target type
     * @return the value, {@code null} when the column is SQL NULL
     * @throws SQLException when the driver cannot read the value
     */
    public static Object read(ResultSet row, int position, Class<?> javaType) throws SQLException {
        Object raw = row.getObject(position);
        if (raw == null) {
            return null;
        }
        if (javaType.isInstance(raw)) {
            return raw;
        }
        // NOTE: PostgreSQL returns timestamptz as OffsetDateTime; Instant is the value type this ORM
        // exposes, so the conversion happens here instead of leaking into entity code.
        if (javaType == Instant.class && raw instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (raw instanceof Number number) {
            Object converted = convertNumber(number, javaType);
            if (converted != null) {
                return converted;
            }
        }
        return row.getObject(position, javaType);
    }

    /**
     * @param number   value as the driver returned it
     * @param javaType target numeric type
     * @return the converted value, or {@code null} when the target is not one of the supported numbers
     */
    private static Object convertNumber(Number number, Class<?> javaType) {
        if (javaType == Long.class) {
            return number.longValue();
        }
        if (javaType == Integer.class) {
            return number.intValue();
        }
        if (javaType == Short.class) {
            return number.shortValue();
        }
        if (javaType == Double.class) {
            return number.doubleValue();
        }
        if (javaType == Float.class) {
            return number.floatValue();
        }
        return null;
    }

    /** Callback that turns a foreign key value into the value stored in an association field. */
    public interface AssociationResolver {

        /**
         * @param field      association field
         * @param foreignKey value read from the foreign key column, {@code null} for SQL NULL
         * @param owner      entity being populated
         */
        void resolve(FieldMetadata field, Object foreignKey, Object owner);

        /**
         * @param field association field
         * @return SQL type of the referenced identifier
         */
        Class<?> columnTypeOf(FieldMetadata field);
    }
}