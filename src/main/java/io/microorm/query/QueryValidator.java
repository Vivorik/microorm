package io.microorm.query;

import io.microorm.exception.MappingException;
import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Validates everything a caller hands to the query builder.
 *
 * <p>This is the second half of the SQL injection defence: column names are checked against metadata
 * and operators against a whitelist enum, and bound values are checked against the type of the column
 * they are compared to. A wrong type fails here, with a readable message, instead of inside PostgreSQL.
 */
public final class QueryValidator {

    private QueryValidator() {
        throw new AssertionError("No instances of QueryValidator");
    }

    /**
     * Resolves a column of an entity.
     *
     * @param entity  metadata of the queried entity
     * @param column  column name
     * @return the mapped field
     * @throws MappingException when the entity has no such column
     */
    public static FieldMetadata field(EntityMetadata entity, String column) {
        return entity.fieldByColumn(column).orElseThrow(() -> new MappingException(
                "Unknown column '" + column + "' for " + entity.describe()
                        + "; known columns: " + entity.fields().stream()
                        .map(FieldMetadata::column).collect(Collectors.joining(", "))));
    }

    /**
     * Parses an operator given as text, e.g. {@code ">"} or {@code "in"}.
     *
     * @param operator operator text
     * @return the matching enum constant
     * @throws MappingException when the operator is not supported
     */
    public static ComparisonOperator operator(String operator) {
        String normalized = operator == null ? "" : operator.trim().toUpperCase(Locale.ROOT);
        for (ComparisonOperator candidate : ComparisonOperator.values()) {
            if (candidate.sql().equals(normalized) || candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new MappingException("Unsupported operator '" + operator + "'; supported: "
                + java.util.Arrays.stream(ComparisonOperator.values())
                .map(ComparisonOperator::sql).collect(Collectors.joining(", ")));
    }

    /**
     * Parses a sort direction given as text.
     *
     * @param direction direction text, case insensitive
     * @return the matching enum constant
     * @throws MappingException when the direction is not supported
     */
    public static SortDirection direction(String direction) {
        try {
            return SortDirection.valueOf(direction.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MappingException("Unsupported sort direction '" + direction
                    + "'; supported: ASC, DESC");
        }
    }

    /**
     * Checks that the values can be compared with the column and converts them where necessary.
     *
     * <p>Numbers are widened to the column type, so an {@code int} literal can be compared with a
     * {@code BIGINT} column. Everything else has to match the column type exactly, which keeps a
     * surprising comparison from reaching the database.
     *
     * @param field      the compared column
     * @param columnType type actually stored in the column, see
     *                   {@link io.microorm.metadata.MetadataRegistry#columnJavaType}
     * @param operator   comparison operator
     * @param values     values to check
     * @return the values converted to the column type
     * @throws MappingException when a value does not fit the column
     */
    public static List<Object> validateValues(
            FieldMetadata field, Class<?> columnType, ComparisonOperator operator, List<Object> values) {
        if (operator.isUnary() && !values.isEmpty()) {
            throw new MappingException("Operator " + operator.sql() + " must not have a value, but got " + values);
        }
        if (!operator.isUnary() && values.isEmpty()) {
            throw new MappingException("Operator " + operator.sql() + " needs a value");
        }
        return values.stream().map(value -> coerce(field, columnType, operator, value)).toList();
    }

    private static Object coerce(FieldMetadata field, Class<?> columnType, ComparisonOperator operator, Object value) {
        if (value == null) {
            return null;
        }
        if (columnType.isInstance(value)) {
            return value;
        }
        if (columnType == Long.class && value instanceof Integer number) {
            return number.longValue();
        }
        // Strings compare with anything: LIKE against a VARCHAR column is the common case, and a
        // numeric column compared with a string is left to the database to reject.
        if (operator == ComparisonOperator.LIKE || operator == ComparisonOperator.NOT_LIKE
                || columnType == String.class) {
            return value;
        }
        throw new MappingException("Value '" + value + "' (" + value.getClass().getSimpleName()
                + ") cannot be compared with column '" + field.column() + "' of type "
                + columnType.getSimpleName());
    }
}
