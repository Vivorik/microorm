package io.microorm.session;

import io.microorm.exception.EntityNotFoundException;
import io.microorm.transaction.Transaction;
import java.util.List;
import java.util.Optional;

/**
 * A unit of work: the API through which MicroORM is used.
 *
 * <p>A session is <strong>not</strong> thread safe and is meant to be short lived, one per request or
 * per unit of work. It owns three things:
 *
 * <ul>
 *   <li>the <em>persistence context</em>, so that the same row is always represented by the same
 *       object within the session ({@link #find});</li>
 *   <li>the <em>unit of work</em>, which detects changed fields on {@link #flush()};</li>
 *   <li>one JDBC connection and at most one transaction.</li>
 * </ul>
 *
 * <p>Mutating operations require an active transaction: MicroORM never silently falls back to
 * auto-commit, because a half-written aggregate is much harder to debug than an immediate error.
 */
public interface Session extends AutoCloseable {

    /**
     * Loads an entity by identifier, returning the very same object for repeated calls.
     *
     * @param type entity class
     * @param id   identifier value
     * @param <T>  entity type
     * @return the managed instance, or empty when there is no such row
     * @throws io.microorm.exception.TransactionRequiredException never; reads work without a
     *                                                            transaction, but pending changes
     *                                                            are flushed first
     */
    <T> Optional<T> find(Class<T> type, Object id);

    /**
     * Same as {@link #find} but fails instead of returning empty.
     *
     * @param type entity class
     * @param id   identifier value
     * @param <T>  entity type
     * @return the managed instance
     * @throws EntityNotFoundException when there is no such row
     */
    <T> T findOrThrow(Class<T> type, Object id);

    /**
     * Loads every row of an entity.
     *
     * <p>Rows already in the persistence context are returned as the managed instances, so the result
     * honours any changes made in this session.
     *
     * @param type entity class
     * @param <T>  entity type
     * @return all rows, never {@code null}
     */
    <T> List<T> findAll(Class<T> type);

    /**
     * Registers a new entity to be inserted on the next {@link #flush()}.
     *
     * @param entity transient instance
     * @throws io.microorm.exception.TransactionRequiredException when no transaction is active
     * @throws io.microorm.exception.PersistenceException        when the entity already exists
     */
    void persist(Object entity);

    /**
     * Copies the state of a detached entity onto a managed one, or attaches it when it is new.
     *
     * @param entity detached instance
     * @return the managed instance
     * @throws io.microorm.exception.TransactionRequiredException when no transaction is active
     */
    Object merge(Object entity);

    /**
     * Schedules an entity for deletion on the next {@link #flush()}.
     *
     * @param entity managed instance
     * @throws io.microorm.exception.TransactionRequiredException when no transaction is active
     */
    void remove(Object entity);

    /**
     * Returns a lazy proxy without touching the database.
     *
     * <p>Touching any property except the identifier triggers the SELECT; touching it after
     * {@link #close()} raises
     * {@link io.microorm.exception.LazyInitializationException}.
     *
     * @param type entity class
     * @param id   identifier value
     * @param <T>  entity type
     * @return a proxy of {@code type}
     */
    <T> T getReference(Class<T> type, Object id);

    /**
     * Executes every pending change: inserts, updates of changed columns and deletions.
     *
     * <p>Called automatically before queries and on {@link #commit()}; calling it explicitly is only
     * needed to surface SQL errors at a specific point in the code.
     *
     * @throws io.microorm.exception.TransactionRequiredException when no transaction is active
     */
    void flush();

    /** Discards the persistence context; managed changes are not written back. */
    void clear();

    /**
     * Starts a transaction, or a savepoint-backed nested one when one is already running.
     *
     * @return the running transaction
     */
    Transaction beginTransaction();

    /**
     * Starts a transaction with an explicit isolation level.
     *
     * @param isolationLevel isolation to request from the driver
     * @return the running transaction
     */
    Transaction beginTransaction(io.microorm.transaction.IsolationLevel isolationLevel);

    /** Flushes and commits the innermost transaction. */
    void commit();

    /** Rolls back the innermost transaction. */
    void rollback();

    /** @return {@code true} while the session may be used */
    boolean isOpen();

    /** @return counters describing how much work this session did, useful in tests and benchmarks */
    SessionStatistics statistics();

    /**
     * Creates a query builder for an entity.
     *
     * @param type entity class
     * @param <T>  entity type
     * @return a new query that reads through this session
     */
    <T> io.microorm.query.Query<T> createQuery(Class<T> type);

    /** @return {@code true} while a transaction is active */
    boolean hasActiveTransaction();

    /**
     * Closes the session: rolls back an unfinished transaction and releases the connection.
     *
     * <p>Lazy proxies created by this session become unusable, which is what makes
     * {@link io.microorm.exception.LazyInitializationException} predictable.
     */
    @Override
    void close();
}