package io.microorm.session;

import io.microorm.exception.EntityNotFoundException;
import io.microorm.exception.PersistenceException;
import io.microorm.id.IdGenerator;
import io.microorm.id.IdGenerators;
import io.microorm.metadata.EntityMetadata;
import io.microorm.query.Query;
import io.microorm.sql.Dialect;
import io.microorm.transaction.IsolationLevel;
import io.microorm.transaction.Transaction;
import io.microorm.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link Session} implementation: lifecycle only.
 *
 * <p>Identity lives in {@link PersistenceContext}, change detection in {@link UnitOfWork}, SELECT and
 * row mapping in {@link RowLoader}, writes in {@link StatementExecutor} and the connection in
 * {@link TransactionManager}. Not thread safe: a session is a unit of work, not shared state.
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
        Optional<Object> target = context.get(metadata.type(), id)
                .or(() -> Optional.ofNullable(rowLoader.find(metadata, id)));
        if (target.isEmpty()) {
            persist(entity);
            return entity;
        }
        Object managed = target.orElseThrow();
        if (managed != entity) {
            RowLoader.copyState(metadata, entity, managed);
        }
        unitOfWork.cancelRemoval(managed);
        log.debug("merge({}#{})", metadata.describe(), id);
        return managed;
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
        Object managed = context.get(metadata.type(), id).orElse(entity);
        unitOfWork.scheduleRemoval(managed);
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
        prepareInserts();
        List<Change> changes = unitOfWork.pendingChanges();
        if (changes.isEmpty()) {
            return;
        }
        counters.flushes++;
        try {
            changes.forEach(statementExecutor::execute);
        } catch (RuntimeException failure) {
            // A failed statement leaves the transaction unusable, so the session stops the caller from
            // continuing on top of half applied work.
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
        Transaction transaction = transactions.requireActiveTransaction("commit");
        flush();
        transaction.commit();
    }

    @Override
    public void rollback() {
        checkOpen();
        transactions.rollback();
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
    public <T> Query<T> createQuery(Class<T> type) {
        checkOpen();
        return new QueryImpl<>(this, type, factory.metadata());
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

    /** @return the row loader, so a query returns managed instances */
    RowLoader rowLoader() {
        return rowLoader;
    }

    /** @return the dialect that renders LIMIT and OFFSET */
    Dialect dialect() {
        return factory.sqlGenerator().dialect();
    }

    /** @return the JDBC connection of this session */
    Connection connection() {
        return transactions.connection();
    }

    /**
     * Prepares entities that are about to be inserted, before the changes are built.
     *
     * <p>Two things happen here, and both must happen before {@link UnitOfWork#pendingChanges()}: the
     * identifier is resolved - so a sequence identifier can be bound as an ordinary column instead of
     * being asked for again - and a nullable {@code @Version} field is initialised to zero, because
     * writing {@code NULL} into a {@code NOT NULL DEFAULT 0} column would fail. Identifiers the
     * database assigns itself stay empty on purpose: the INSERT then carries a RETURNING clause.
     */
    private void prepareInserts() {
        for (Object entity : unitOfWork.scheduledInserts()) {
            EntityMetadata metadata = metadataOf(entity);
            metadata.version()
                    .filter(field -> field.getValue(entity) == null)
                    .ifPresent(field -> field.setValue(entity, FieldSnapshot.initialVersion(field.javaType())));
            if (!metadata.hasGeneratedIdentifier() || metadata.identifier().getValue(entity) != null) {
                continue;
            }
            try {
                IdGenerator generator = idGenerators.forEntity(metadata);
                generator.generate(transactions.connection(), metadata)
                        .ifPresent(id -> metadata.identifier().setValue(entity, id));
            } catch (SQLException e) {
                throw new PersistenceException("Cannot generate an identifier for "
                        + metadata.describe(), e);
            }
        }
    }

    /**
     * Flushes pending work when a transaction is running, so that a read never observes values changed
     * in memory but not written yet - Hibernate calls this {@code FlushMode.AUTO}.
     */
    void autoFlush() {
        if (transactions.hasActiveTransaction() && unitOfWork.hasPendingWork()) {
            flush();
        }
    }

    private EntityMetadata metadataOf(Object entity) {
        return metadataOf(entity.getClass());
    }

    EntityMetadata metadataOf(Class<?> type) {
        return factory.metadata().metadataFor(type);
    }

    private void checkOpen() {
        if (closed) {
            throw new PersistenceException("Session is closed");
        }
    }
}