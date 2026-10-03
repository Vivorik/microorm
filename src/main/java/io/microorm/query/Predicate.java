package io.microorm.query;

import io.microorm.metadata.FieldMetadata;
import java.util.List;

/**
 * One condition of a WHERE clause.
 *
 * <p>The column is a {@link FieldMetadata}, so it is known to exist in the entity: a predicate can
 * never carry an unknown or injected column name. The values are plain objects that end up as bind
 * parameters.
 *
 * @param logicalOperator how this condition is joined with the previous one
 * @param field           the mapped field the condition applies to
 * @param operator        the comparison operator
 * @param values          values to bind, empty for unary operators, several for {@code IN}
 */
public record Predicate(
        LogicalOperator logicalOperator,
        FieldMetadata field,
        ComparisonOperator operator,
        List<Object> values) {

    public Predicate {
        // NOTE: List.copyOf rejects null elements, and comparing a column with NULL is legal, so the
        // list is copied without that restriction.
        values = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
        if (!operator.isUnary() && values.isEmpty()) {
            throw new IllegalArgumentException("Operator " + operator + " needs at least one value");
        }
        if (operator.isUnary() && !values.isEmpty()) {
            throw new IllegalArgumentException("Operator " + operator + " must not have values");
        }
    }

    /**
     * @param operator how to join this condition with the previous one
     * @param field    mapped field
     * @param operator2 comparison operator
     * @param values   values to bind
     * @return the predicate
     */
    public static Predicate of(LogicalOperator operator, FieldMetadata field,
            ComparisonOperator operator2, List<Object> values) {
        return new Predicate(operator, field, operator2, values);
    }

    /**
     * @param field   mapped field
     * @param operator comparison operator
     * @param value   single value to bind
     * @return the predicate
     */
    public static Predicate of(FieldMetadata field, ComparisonOperator operator, Object value) {
        return new Predicate(LogicalOperator.AND, field, operator, List.of(value));
    }
}
