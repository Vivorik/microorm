package io.microorm.query;

import io.microorm.exception.NonUniqueResultException;
import java.util.List;
import java.util.Optional;

/**
 * Fluent query over one entity type.
 *
 * <p>Example:
 *
 * <pre>{@code
 * List<User> adults = session.createQuery(User.class)
 *         .where("age", ">", 18)
 *         .and("active", "=", true)
 *         .orderBy("name", SortDirection.ASC)
 *         .limit(10)
 *         .offset(20)
 *         .list();
 * }</pre>
 *
 * <p>Column names are validated against the entity metadata and values are always sent as bind
 * parameters, so no part of the query can become SQL text.
 *
 * <p>A query is not thread safe and belongs to the session that created it. Like any read inside a
 * transaction it flushes pending changes first.
 *
 * @param <T> entity type
 */
public interface Query<T> {

    /**
     * Starts the WHERE clause.
     *
     * @param column   column name
     * @param operator operator, either the SQL text ({@code ">="}) or the enum name
     * @param value    value to bind
     * @return this query
     */
    Query<T> where(String column, String operator, Object value);

    /**
     * Starts the WHERE clause.
     *
     * @param column   column name
     * @param operator comparison operator
     * @param value    value to bind
     * @return this query
     */
    Query<T> where(String column, ComparisonOperator operator, Object value);

    /**
     * Adds an {@code AND} condition.
     *
     * @param column   column name
     * @param operator comparison operator
     * @param value    value to bind
     * @return this query
     */
    Query<T> and(String column, ComparisonOperator operator, Object value);

    /**
     * Adds an {@code OR} condition.
     *
     * @param column   column name
     * @param operator comparison operator
     * @param value    value to bind
     * @return this query
     */
    Query<T> or(String column, ComparisonOperator operator, Object value);

    /**
     * Adds an {@code AND} condition with the operator given as text.
     *
     * @param column   column name
     * @param operator operator text
     * @param value    value to bind
     * @return this query
     */
    Query<T> and(String column, String operator, Object value);

    /**
     * Adds an {@code OR} condition with the operator given as text.
     *
     * @param column   column name
     * @param operator operator text
     * @param value    value to bind
     * @return this query
     */
    Query<T> or(String column, String operator, Object value);

    /**
     * Restricts the result to rows whose column is in the given values.
     *
     * @param column column name
     * @param values allowed values
     * @return this query
     */
    Query<T> in(String column, Object... values);

    /**
     * Restricts the result to rows whose column is not in the given values.
     *
     * @param column column name
     * @param values rejected values
     * @return this query
     */
    Query<T> notIn(String column, Object... values);

    /**
     * Keeps only rows whose column is {@code NULL}.
     *
     * @param column column name
     * @return this query
     */
    Query<T> isNull(String column);

    /**
     * Keeps only rows whose column is not {@code NULL}.
     *
     * @param column column name
     * @return this query
     */
    Query<T> isNotNull(String column);

    /**
     * Adds an {@code ORDER BY} item. Can be called several times; the order of the calls is the order
     * of the sort keys.
     *
     * @param column    column name
     * @param direction sort direction
     * @return this query
     */
    Query<T> orderBy(String column, SortDirection direction);

    /**
     * Adds an {@code ORDER BY} item in ascending order.
     *
     * @param column column name
     * @return this query
     */
    Query<T> orderByAsc(String column);

    /**
     * Limits the number of returned rows.
     *
     * @param limit maximum number of rows, must not be negative
     * @return this query
     */
    Query<T> limit(int limit);

    /**
     * Skips the first rows of the result.
     *
     * @param offset number of rows to skip, must not be negative
     * @return this query
     */
    Query<T> offset(int offset);

    /**
     * Executes the query.
     *
     * @return the rows, never {@code null}
     */
    List<T> list();

    /**
     * Executes the query and returns the first row.
     *
     * @return the first row, empty when there is none
     */
    Optional<T> first();

    /**
     * Executes the query and requires exactly one row.
     *
     * @return the single row
     * @throws NonUniqueResultException when the query matched zero or several rows
     */
    T single();

    /** @return number of matching rows */
    long count();

    /** @return {@code true} when at least one row matches, without loading it */
    boolean exists();
}
