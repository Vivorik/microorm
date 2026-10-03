package io.microorm.sql;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds SQL text for the four statements MicroORM needs.
 *
 * <p>Two invariants make this class safe to use with any input:
 *
 * <ul>
 *   <li>identifiers come from {@link EntityMetadata}, which only contains names that passed
 *       {@link io.microorm.metadata.IdentifierValidator};</li>
 *   <li>values never appear in SQL text at all; they are returned as {@link Bind} parameters in
 *       placeholder order.</li>
 * </ul>
 *
 * <p>The generator is deliberately free of JDBC: it maps metadata and values to text, and the
 * session decides which values take part in a statement. That split is what makes every branch here
 * unit-testable.
 */
public final class SqlGenerator {

    private final Dialect dialect;

    public SqlGenerator(Dialect dialect) {
        this.dialect = dialect;
    }

    /** @return the dialect this generator renders SQL for */
    public Dialect dialect() {
        return dialect;
    }

    /**
     * Builds an INSERT for a new entity.
     *
     * @param entity metadata of the entity
     * @param values columns to write, in a stable order
     * @return the statement; when the identifier is database-generated the statement carries a
     *         {@code RETURNING} clause
     */
    public InsertStatement insert(EntityMetadata entity, List<ColumnValue> values) {
        String columns = values.stream().map(value -> value.field().column())
                .collect(Collectors.joining(", "));
        String placeholders = values.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + entity.tableName() + " (" + columns + ") VALUES (" + placeholders + ")";
        // NOTE: RETURNING is only needed when the database assigns the identifier. If the identifier
        // is already one of the bound columns - because the application set it, or because a sequence
        // strategy produced it - reading it back would be a wasted round trip.
        boolean identifierWritten = values.stream().anyMatch(value -> value.field().identifier());
        Optional<String> returning = identifierWritten || !entity.hasGeneratedIdentifier()
                ? Optional.empty()
                : Optional.of(entity.identifier().column());
        boolean useReturning = returning.isPresent() && dialect.supportsReturningClause();
        if (useReturning) {
            sql = sql + " RETURNING " + returning.get();
        }
        return new InsertStatement(sql, binds(values), useReturning ? returning : Optional.empty());
    }

    /**
     * Builds an UPDATE that touches only the given columns.
     *
     * @param entity        metadata of the entity
     * @param changed       dirty columns with their new values
     * @param id            identifier value
     * @param version       expected version value, empty for entities without {@code @Version}
     * @return the statement
     * @throws IllegalArgumentException when there is nothing to update
     */
    public UpdateStatement update(EntityMetadata entity, List<ColumnValue> changed, Object id, Optional<Object> version) {
        if (changed.isEmpty()) {
            throw new IllegalArgumentException("UPDATE of " + entity.describe() + " has no changed columns");
        }
        String assignments = changed.stream()
                .map(value -> value.field().column() + " = ?")
                .collect(Collectors.joining(", "));
        List<Bind> parameters = new java.util.ArrayList<>(binds(changed));
        StringBuilder where = new StringBuilder(entity.identifier().column()).append(" = ?");
        parameters.add(new Bind(id, entity.identifier().javaType()));
        if (version.isPresent()) {
            where.append(" AND ").append(entity.version().orElseThrow().column()).append(" = ?");
            parameters.add(new Bind(version.get(), entity.version().orElseThrow().javaType()));
        }
        String sql = "UPDATE " + entity.tableName() + " SET " + assignments + " WHERE " + where;
        return new UpdateStatement(sql, parameters, version.isPresent());
    }

    /**
     * Builds a DELETE by identifier.
     *
     * @param entity  metadata of the entity
     * @param id      identifier value
     * @param version expected version value, empty for entities without {@code @Version}
     * @return the statement
     */
    public DeleteStatement delete(EntityMetadata entity, Object id, Optional<Object> version) {
        List<Bind> parameters = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(entity.identifier().column()).append(" = ?");
        parameters.add(new Bind(id, entity.identifier().javaType()));
        if (version.isPresent()) {
            where.append(" AND ").append(entity.version().orElseThrow().column()).append(" = ?");
            parameters.add(new Bind(version.get(), entity.version().orElseThrow().javaType()));
        }
        return new DeleteStatement("DELETE FROM " + entity.tableName() + " WHERE " + where, parameters,
                version.isPresent());
    }

    /**
     * Builds a SELECT of a single row by identifier.
     *
     * @param entity metadata of the entity
     * @param id     identifier value
     * @return the statement
     */
    public SelectStatement selectById(EntityMetadata entity, Object id) {
        return new SelectStatement("SELECT " + entity.selectColumns() + " FROM " + entity.tableName()
                + " WHERE " + entity.identifier().column() + " = ?",
                List.of(new Bind(id, entity.identifier().javaType())), entity);
    }

    /**
     * Builds a SELECT of every row.
     *
     * @param entity metadata of the entity
     * @return the statement
     */
    public SelectStatement selectAll(EntityMetadata entity) {
        return new SelectStatement("SELECT " + entity.selectColumns() + " FROM " + entity.tableName(),
                List.of(), entity);
    }

    /**
     * Builds a SELECT with a caller-supplied clause.
     *
     * @param entity     metadata of the entity
     * @param clause     text appended after the table name, already validated by the query builder
     * @param parameters bind parameters of the clause
     * @return the statement
     */
    public SelectStatement select(EntityMetadata entity, String clause, List<Bind> parameters) {
        String sql = "SELECT " + entity.selectColumns() + " FROM " + entity.tableName();
        if (!clause.isBlank()) {
            sql = sql + " " + clause;
        }
        return new SelectStatement(sql, parameters, entity);
    }

    /**
     * Builds {@code SELECT count(*)}.
     *
     * @param entity     metadata of the entity
     * @param clause     text appended after the table name, already validated
     * @param parameters bind parameters of the clause
     * @return the statement
     */
    public String count(EntityMetadata entity, String clause, List<Bind> parameters) {
        String sql = "SELECT count(*) FROM " + entity.tableName();
        if (!clause.isBlank()) {
            sql = sql + " " + clause;
        }
        return sql;
    }

    /**
     * Builds the {@code nextval('sequence')} expression used by the sequence id generator.
     *
     * @param sequenceName name of the sequence, already validated as an identifier
     * @return SQL expression
     */
    public String nextSequenceValue(String sequenceName) {
        return dialect.sequenceNextValue(sequenceName);
    }

    private List<Bind> binds(List<ColumnValue> values) {
        return values.stream()
                .map(value -> new Bind(value.value(), value.columnType()))
                .toList();
    }

    /** @return the fields of an entity that a fresh INSERT has to write */
    public static List<FieldMetadata> insertableColumns(EntityMetadata entity) {
        return entity.insertableFields();
    }
}