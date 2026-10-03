package io.microorm.session;

import io.microorm.exception.EntityNotFoundException;
import io.microorm.exception.PersistenceException;
import io.microorm.id.IdGenerators;
import io.microorm.metadata.EntityMetadata;
import io.microorm.transaction.IsolationLevel;
import io.microorm.transaction.Transaction;
import io.microorm.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link Session} implementation.
 *
 * <p>Deliberately thin: the interesting work lives in the collaborators it owns, which is how the
 * class stays readable and how each part can be tested on its own.
 *
 * <ul>
 *   <li>{@link PersistenceContext} - identity: one object per row per session;</li>
 *   <li>{@link UnitOfWork} - change detection: which columns actually changed;</li>
 *   <li>{@link RowLoader} - SELECT execution and row mapping;</li>
 *   <li>{@link StatementExecutor} - INSERT, UPDATE and DELETE, including optimistic locking;</li>
 *   <li>{@link TransactionManager} - connection ownership and transactions.</li>
 * </ul>
 *
 * <p>Not thread safe, by design: a session is a unit of work, not a container of shared state.
 */
public final class SessionImpl implements Session {

    private static final Logger log = LoggerFactory.getLogger(SessionImpl.class);

    private final SessionFactory factory;
    private final TransactionManager transactions;
    private final PersistenceContext context;
    private final UnitOfWork unitOfWork;
    private final RowLoader rowLoader;
    private final StatementExecutor statementExecutor;
    private final IdGenerators idGenerators;
    private final SessionCounters counters = new SessionCounters();

    private boolean closed;

    SessionImpl(SessionFactory factory, PersistenceContext context) {
        this.factory = factory;
        this.context = context;
        this.transactions = new TransactionManager(
                factory.dataSource(), factory.isolationLevel(), factory.invalidator());
        this.unitOfWork = new UnitOfWork(context, factory.metadata());
        this.idGenerators = factory.idGenerators();
        this.rowLoader = new RowLoader(factory.metadata(), factory.sqlGenerator(), context, unitOfWork,
                factory.proxyFactory(), counters, transactions::connection, () -> !closed);
        this.statementExecutor = new StatementExecutor(factory.sqlGenerator(), context, unitOfWork,
                counters, transactions::connection);
    }

    @Override
    public <T> Optional<T> find(Class<T> type, Object id) {
        checkOpen();
        autoFlush();
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rowLoader.find(metadataOf(type), id)).map(type::cast);
    }

    @Override
    public <T> T findOrThrow(Class<T> type, Object id) {
        return find(type, id).orElseThrow(() -> new EntityNotFoundException(type, id));
    }

    @Override
    public <T> List<T> findAll(Class<T> type) {
        checkOpen();
        autoFlush();
        List<T> result = new ArrayList<>();
        for (Object entity : rowLoader.load(factory.sqlGenerator().selectAll(metadataOf(type)))) {
            result.add(type.cast(entity));
        }
        return result;
    }

    @Override
    public void persist(Object entity) {
        checkOpen();
        transactions.requireActiveTransaction("persist an entity");
        EntityMetadata metadata = metadataOf(entity);
        Object id = metadata.identifier().getValue(entity);
        if (id != null && context.get(metadata.type(), id).isPresent()) {
            throw new PersistenceException(metadata.describe() + " with id " + id
                    + " is already managed in this session; use merge() to update it");
        }
        unitOfWork.scheduleInsert(entity);
        if (id != null) {
            context.put(metadata.type(), id, entity);
        }
        log.debug("persist({})", metadata.describe());
    }

    @Override
    public Object merge(Object entity) {
        checkOpen();
        transactions.requireActiveTransaction("merge an entity");
        EntityMetadata metadata = metadataOf(entity);
        Object id = metadata.identifier().getValue(entity);
        if (id == null) {
            persist(entity);
            return entity;
        }
        Object target = context.get(metadata.type(), id)
                .orElseGet(() -> Optional.ofNullable(rowLoader.find(metadata, id)).orElse(null));
        if (target == null) {
            persist(entity);
            return entity;
        }
        if (target != entity) {
            RowLoader.copyState(metadata, entity, target);
        }
        unitOfWork.cancelRemoval(target);
        log.debug("merge({}#{})", metadata.describe(), id);
        return target;
    }

    @Override
    public void remove(Object entity) {
        checkOpen();
        transactions.requireActiveTransaction("remove an entity");
        EntityMetadata metadata = metadataOf(entity);
        Object id = metadata.identifier().getValue(entity);
        if (id == null) {
            throw new PersistenceException("Cannot remove a transient " + metadata.describe());
        }
        unitOfWork.scheduleRemoval(context.get(metadata.type(), id).orElse(entity));
        log.debug("remove({}#{})", metadata.describe(), id);
    }

    @Override
    public <T> T getReference(Class<T> type, Object id) {
        checkOpen();
        if (id == null) {
            throw new PersistenceException("getReference(" + type.getSimpleName() + ") needs an id");
        }
        Optional<Object> managed = context.get(type, id);
        if (managed.isPresent()) {
            return type.cast(managed.get());
        }
        EntityMetadata metadata = metadataOf(type);
        return factory.proxyFactory().createProxy(metadata, id, proxyId -> rowLoader.loadReference(metadata, proxyId));
    }

    @Override
    public void flush() {
        checkOpen();
        transactions.requireActiveTransaction("flush");
        assignIdentifiers();
        List<Change> changes = unitOfWork.pendingChanges();
        if (changes.isEmpty()) {
            return;
        }
        counters.flushes++;
        try {
            changes.forEach(statementExecutor::execute);
        } catch (RuntimeException failure) {
            // A failed statement leaves the transaction unusable, so the session makes sure the caller
            // cannot keep going on top of half applied work.
            transactions.current().ifPresent(Transaction::markRollbackOnly);
            transactions.markConnectionBroken(String.valueOf(failure.getMessage()));
            throw failure;
        }
    }

    @Override
    public void clear() {
        checkOpen();
        context.clear();
        unitOfWork.clear();
    }

    @Override
    public Transaction beginTransaction() {
        checkOpen();
        return transactions.begin();
    }

    @Override
    public Transaction beginTransaction(IsolationLevel isolationLevel) {
        checkOpen();
        return transactions.begin(isolationLevel);
    }

    @Override
    public void commit() {
        checkOpen();
        transactions.requireActiveTransaction("commit");
        flush();
        transactions.requireActiveTransaction("commit").commit();
    }

    @Override
    public void rollback() {
        checkOpen();
        transactions.requireActiveTransaction("roll back").rollback();
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean hasActiveTransaction() {
        return transactions.hasActiveTransaction();
    }

    @Override
    public SessionStatistics statistics() {
        return counters.snapshot();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        transactions.close();
        context.clear();
        unitOfWork.clear();
        log.debug("Session closed: {}", counters.snapshot());
    }

    /** @return the persistence context of this session, used by tests and by the query builder */
    PersistenceContext persistenceContext() {
        return context;
    }

    /** @return the unit of work of this session, used by the query builder to flush first */
    UnitOfWork unitOfWork() {
        return unitOfWork;
    }

    /**
     * Resolves database generated identifiers before the changes are built.
     *
     * <p>The order matters: once the identifier is known, the INSERT can bind it as an ordinary column
     * instead of asking the database to return it, and the persistence context gets a key for the
     * entity. Identifiers that the database assigns itself are left empty on purpose - the INSERT then
     * carries a RETURNING clause.
     */
    private void assignIdentifiers() {
        for (Object entity : unitOfWork.scheduledInserts()) {
            EntityMetadata metadata = metadataOf(entity);
            if (!metadata.hasGeneratedIdentifier() || metadata.identifier().getValue(entity) != null) {
                continue;
            }
            try {
                idGenerators.forEntity(metadata).generate(transactions.connection(), metadata)
                        .ifPresent(id -> metadata.identifier().setValue(entity, id));
            } catch (java.sql.SQLException e) {
                throw new PersistenceException("Cannot generate an identifier for "
                        + metadata.describe(), e);
            }
        }
    }

    /**
     * Flushes pending work when a transaction is running.
     *
     * <p>Reading inside a transaction must not observe values that were already changed in memory but
     * not written yet; this is the behaviour Hibernate calls {@code FlushMode.AUTO}.
     */
    private void autoFlush() {
        if (transactions.hasActiveTransaction() && unitOfWork.hasPendingWork()) {
            flush();
        }
    }

    private EntityMetadata metadataOf(Object entity) {
        return factory.metadata().metadataFor(entity.getClass());
    }

    private EntityMetadata metadataOf(Class<?> type) {
        return factory.metadata().metadataFor(type);
    }

    private void checkOpen() {
        if (closed) {
            throw new PersistenceException("Session is closed");
        }
    }
}