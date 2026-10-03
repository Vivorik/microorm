package io.microorm.query;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import io.microorm.sql.Bind;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the {@code WHERE} clause of a query and collects its bind parameters.
 *
 * <p>Every condition is rendered from a {@link FieldMetadata} and a {@link ComparisonOperator}, so the
 * only text that reaches SQL is metadata the entity actually owns plus the operator's own SQL. Values
 * are never rendered: they are appended as {@link Bind} parameters in the same order the placeholders
 * appear.
 *
 * <p>Conditions are grouped strictly left to right - {@code a AND b OR c} means {@code (a AND b) OR c} -
 * which is what SQL itself means, and what makes the fluent API predictable without parentheses.
 */
public final class WhereClause {

    private final EntityMetadata entity;
    private final io.microorm.metadata.MetadataRegistry registry;
    private final List<Predicate> predicates = new ArrayList<>();

    public WhereClause(EntityMetadata entity, io.microorm.metadata.MetadataRegistry registry) {
        this.entity = entity;
        this.registry = registry;
    }

    /**
     * Appends a condition.
     *
     * @param logicalOperator how to join it with the previous conditions
     * @param column          column name, must be a mapped column of the entity
     * @param operator        comparison operator
     * @param values          values to bind, ignored for unary operators
     * @return this clause
     * @throws io.microorm.exception.MappingException when the column is unknown or a value has the
     *                                               wrong type
     */
    public WhereClause add(LogicalOperator logicalOperator, String column,
            ComparisonOperator operator, List<Object> values) {
        FieldMetadata field = QueryValidator.field(entity, column);
        List<Object> converted = QueryValidator.validateValues(field, registry.columnJavaType(field),
                operator, values);
        predicates.add(Predicate.of(logicalOperator, field, operator, converted));
        return this;
    }

    /**
     * Appends an {@code IN} condition.
     *
     * @param logicalOperator how to join it with the previous conditions
     * @param column          column name
     * @param values          values to bind
     * @return this clause
     */
    public WhereClause addIn(LogicalOperator logicalOperator, String column, List<Object> values) {
        return add(logicalOperator, column, ComparisonOperator.IN, values);
    }

    /**
     * Appends a {@code NOT IN} condition.
     *
     * @param logicalOperator how to join it with the previous conditions
     * @param column          column name
     * @param values          values to bind
     * @return this clause
     */
    public WhereClause addNotIn(LogicalOperator logicalOperator, String column, List<Object> values) {
        return add(logicalOperator, column, ComparisonOperator.NOT_IN, values);
    }

    /** @return the SQL text, empty when there is no condition */
    public String clause() {
        if (predicates.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(predicates.size());
        for (int i = 0; i < predicates.size(); i++) {
            parts.add(render(predicates.get(i), i));
        }
        return "WHERE " + String.join(" ", parts);
    }

    /** @return the bind parameters in placeholder order */
    public List<Bind> parameters() {
        List<Bind> binds = new ArrayList<>();
        for (Predicate predicate : predicates) {
            for (Object value : predicate.values()) {
                binds.add(new Bind(value, registry.columnJavaType(predicate.field())));
            }
        }
        return binds;
    }

    /** @return number of conditions */
    public int size() {
        return predicates.size();
    }

    /** @return the conditions, in the order they were added */
    public List<Predicate> predicates() {
        return List.copyOf(predicates);
    }

    private String render(Predicate predicate, int index) {
        StringBuilder sql = new StringBuilder();
        if (index > 0) {
            sql.append(predicate.logicalOperator().sql()).append(' ');
        }
        sql.append(predicate.field().column()).append(' ').append(predicate.operator().sql());
        if (predicate.operator().isMultiValued()) {
            sql.append(predicate.values().stream().map(ignored -> "?").collect(Collectors.joining(", ", " (", ")")));
        } else if (!predicate.operator().isUnary()) {
            sql.append(" ?");
        }
        return sql.toString();
    }
}
