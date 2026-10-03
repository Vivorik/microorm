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
     * @param row      positioned result set
     * @param position one-based column index
     * @param javaType target type
     * @return the value, {@code null} when the column is SQL NULL
     * @throws SQLException when the driver cannot convert the value
     */
    public static Object read(ResultSet row, int position, Class<?> javaType) throws SQLException {
        Object raw = row.getObject(position);
        if (raw == null) {
            return null;
        }
        // NOTE: PostgreSQL returns timestamptz as OffsetDateTime; Instant is the value type this ORM
        // exposes, so the conversion happens here instead of leaking into entity code.
        if (javaType == Instant.class && raw instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (javaType.isInstance(raw)) {
            return raw;
        }
        return row.getObject(position, javaType);
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