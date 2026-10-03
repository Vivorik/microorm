package io.microorm.session;

import io.microorm.exception.NonUniqueResultException;
import io.microorm.metadata.EntityMetadata;
import io.microorm.query.ComparisonOperator;
import io.microorm.query.LogicalOperator;
import io.microorm.query.Query;
import io.microorm.query.QueryValidator;
import io.microorm.query.SortDirection;
import io.microorm.query.WhereClause;
import io.microorm.sql.Bind;
import io.microorm.sql.ParameterBinder;
import io.microorm.sql.SelectStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link Query} implementation.
 *
 * <p>It lives in the session package because a query is not standalone: it flushes the unit of work
 * before reading and reuses the session's row loader, so that rows it returns are managed instances.
 * A query built with one session must not be executed with another.
 *
 * @param <T> entity type
 */
final class QueryImpl<T> implements Query<T> {

    private static final Logger log = LoggerFactory.getLogger(QueryImpl.class);

    private final SessionImpl session;
    private final Class<T> type;
    private final EntityMetadata entity;
    private final WhereClause where;

    private final List<String> orderBy = new ArrayList<>();
    private Integer limit;
    private Integer offset;

    QueryImpl(SessionImpl session, Class<T> type) {
        this.session = session;
        this.type = type;
        this.entity = session.metadata().metadataFor(type);
        this.where = new WhereClause(entity, session.metadata());
    }

    @Override
    public Query<T> where(String column, String operator, Object value) {
        return where(column, QueryValidator.operator(operator), value);
    }

    @Override
    public Query<T> where(String column, ComparisonOperator operator, Object value) {
        return withUnaryValues(operator, value)
                ? add(LogicalOperator.AND, column, operator, List.of())
                : add(LogicalOperator.AND, column, operator, List.of(value));
    }

    @Override
    public Query<T> and(String column, ComparisonOperator operator, Object value) {
        return withUnaryValues(operator, value)
                ? add(LogicalOperator.AND, column, operator, List.of())
                : add(LogicalOperator.AND, column, operator, List.of(value));
    }

    @Override
    public Query<T> or(String column, ComparisonOperator operator, Object value) {
        return withUnaryValues(operator, value)
                ? add(LogicalOperator.OR, column, operator, List.of())
                : add(LogicalOperator.OR, column, operator, List.of(value));
    }

    @Override
    public Query<T> and(String column, String operator, Object value) {
        return and(column, QueryValidator.operator(operator), value);
    }

    @Override
    public Query<T> or(String column, String operator, Object value) {
        return or(column, QueryValidator.operator(operator), value);
    }

    @Override
    public Query<T> in(String column, Object... values) {
        where.addIn(LogicalOperator.AND, column, List.of(values));
        return this;
    }

    @Override
    public Query<T> notIn(String column, Object... values) {
        where.addNotIn(LogicalOperator.AND, column, List.of(values));
        return this;
    }

    @Override
    public Query<T> isNull(String column) {
        return add(LogicalOperator.AND, column, ComparisonOperator.IS_NULL, List.of());
    }

    @Override
    public Query<T> isNotNull(String column) {
        return add(LogicalOperator.AND, column, ComparisonOperator.IS_NOT_NULL, List.of());
    }

    @Override
    public Query<T> orderBy(String column, SortDirection direction) {
        orderBy.add(QueryValidator.field(entity, column).column() + " " + direction.sql());
        return this;
    }

    @Override
    public Query<T> orderByAsc(String column) {
        return orderBy(column, SortDirection.ASC);
    }

    @Override
    public Query<T> limit(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative but was " + limit);
        }
        this.limit = limit;
        return this;
    }

    @Override
    public Query<T> offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative but was " + offset);
        }
        this.offset = offset;
        return this;
    }

    @Override
    public List<T> list() {
        session.autoFlush();
        List<T> result = new ArrayList<>();
        for (Object entity : session.rowLoader().load(statement())) {
            result.add(type.cast(entity));
        }
        return result;
    }

    @Override
    public Optional<T> first() {
        limit(1);
        return list().stream().findFirst();
    }

    @Override
    public T single() {
        List<T> rows = list();
        if (rows.size() != 1) {
            throw new NonUniqueResultException("Expected exactly one " + entity.describe()
                    + " but the query returned " + rows.size());
        }
        return rows.get(0);
    }

    @Override
    public long count() {
        // An aggregate always returns a row in SQL, but a defensive zero keeps the contract clear:
        // count() never returns null.
        Long count = scalar("count(*)", Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public boolean exists() {
        // "SELECT 1 ... LIMIT 1" instead of count(*): the caller only wants a yes or no, and the
        // database can stop after the first row instead of counting all of them.
        session.autoFlush();
        String sql = "SELECT 1 FROM " + entity.tableName() + clauseSuffix() + limitSuffix(1);
        try (PreparedStatement prepared = session.connection().prepareStatement(sql)) {
            ParameterBinder.bind(prepared, where.parameters());
            log.debug("{} {}", sql, where.parameters());
            try (ResultSet rows = prepared.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new io.microorm.exception.PersistenceException(
                    "Cannot check whether " + entity.describe() + " exists", e);
        }
    }

    /**
     * Runs a single-column aggregate.
     *
     * @param expression the aggregate to select
     * @param type       expected type of the result
     * @param <R>        result type
     * @return the aggregated value, or {@code null} when the query returned no row
     */
    private <R> R scalar(String expression, Class<R> type) {
        session.autoFlush();
        String sql = "SELECT " + expression + " FROM " + entity.tableName() + clauseSuffix();
        try (PreparedStatement prepared = session.connection().prepareStatement(sql)) {
            ParameterBinder.bind(prepared, where.parameters());
            log.debug("{} {}", sql, where.parameters());
            try (ResultSet rows = prepared.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Object value = rows.getObject(1);
                return value == null ? null : type.cast(value instanceof Number number
                        ? number.longValue() : value);
            }
        } catch (SQLException e) {
            throw new io.microorm.exception.PersistenceException(
                    "Cannot aggregate " + entity.describe(), e);
        }
    }

    private SelectStatement statement() {
        return new SelectStatement("SELECT " + entity.selectColumns() + " FROM " + entity.tableName()
                + clauseSuffix(), where.parameters(), entity);
    }

    /**
     * Renders everything that follows the table name.
     *
     * @return the clause, starting with a space when it is not empty
     */
    private String clauseSuffix() {
        StringBuilder clause = new StringBuilder();
        append(clause, where.clause());
        if (!orderBy.isEmpty()) {
            append(clause, "ORDER BY " + String.join(", ", orderBy));
        }
        append(clause, session.dialect().pagination(limit, offset));
        return clause.toString();
    }

    private String limitSuffix(int rowLimit) {
        String pagination = session.dialect().pagination(rowLimit, null);
        return pagination.isBlank() ? "" : " " + pagination;
    }

    private void append(StringBuilder clause, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        clause.append(' ').append(part);
    }

    private Query<T> add(LogicalOperator operator, String column, ComparisonOperator comparison, List<Object> values) {
        where.add(operator, column, comparison, values);
        return this;
    }

    private boolean withUnaryValues(ComparisonOperator operator, Object value) {
        return operator.isUnary() && value == null;
    }
}