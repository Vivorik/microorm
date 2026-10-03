package io.microorm.session;

import io.microorm.exception.OptimisticLockException;
import io.microorm.exception.PersistenceException;
import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import io.microorm.sql.ColumnValue;
import io.microorm.sql.DeleteStatement;
import io.microorm.sql.EntityRowMapper;
import io.microorm.sql.InsertStatement;
import io.microorm.sql.ParameterBinder;
import io.microorm.sql.UpdateStatement;
import io.microorm.sql.SqlGenerator;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the {@link Change}s of a unit of work into INSERT, UPDATE and DELETE statements.
 *
 * <p>Isolating statement execution here has two benefits: the optimistic locking rule ("zero affected
 * rows means somebody else won") lives in exactly one place, and the session is left with lifecycle
 * logic only.
 */
final class StatementExecutor {

    private static final Logger log = LoggerFactory.getLogger(StatementExecutor.class);

    private final SqlGenerator sqlGenerator;
    private final PersistenceContext context;
    private final UnitOfWork unitOfWork;
    private final SessionCounters counters;
    private final SqlConnectionProvider connections;

    StatementExecutor(
            SqlGenerator sqlGenerator,
            PersistenceContext context,
            UnitOfWork unitOfWork,
            SessionCounters counters,
            SqlConnectionProvider connections) {
        this.sqlGenerator = sqlGenerator;
        this.context = context;
        this.unitOfWork = unitOfWork;
        this.counters = counters;
        this.connections = connections;
    }

    /**
     * Executes one change.
     *
     * @param change change produced by the unit of work
     * @throws PersistenceException        when the database rejects the statement
     * @throws OptimisticLockException     when the row was modified by another transaction
     */
    void execute(Change change) {
        switch (change) {
            case Change.Insert insert -> insert(insert);
            case Change.Update update -> update(update);
            case Change.Delete delete -> delete(delete);
        }
    }

    private void insert(Change.Insert change) {
        EntityMetadata metadata = change.entity();
        Object instance = change.instance();
        InsertStatement statement = sqlGenerator.insert(metadata, change.columns());
        try (PreparedStatement prepared = connections.connection().prepareStatement(statement.sql())) {
            ParameterBinder.bind(prepared, statement.parameters());
            log.debug("{} {}", statement.sql(), statement.parameters());
            if (statement.returnsGeneratedKeys()) {
                try (ResultSet keys = prepared.executeQuery()) {
                    if (!keys.next()) {
                        throw new PersistenceException("INSERT of " + metadata.describe()
                                + " returned no generated identifier");
                    }
                    Object id = EntityRowMapper.read(keys, 1, metadata.identifier().javaType());
                    metadata.identifier().setValue(instance, id);
                }
            } else {
                prepared.executeUpdate();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Cannot insert " + metadata.describe(), e);
        }
        context.put(metadata.type(), metadata.identifier().getValue(instance), instance);
        unitOfWork.refreshSnapshot(instance);
        counters.insertsIssued++;
    }

    private void update(Change.Update change) {
        EntityMetadata metadata = change.entity();
        UpdateStatement statement = sqlGenerator.update(metadata, change.columns(), change.id(),
                change.version());
        int affected = executeUpdate(statement, "update " + metadata.describe());
        if (affected == 0) {
            throw new OptimisticLockException("Update of " + metadata.describe() + " with id "
                    + change.id() + " affected no rows: it was modified or deleted by another transaction",
                    change.id());
        }
        applyNewVersion(metadata, change);
        unitOfWork.refreshSnapshot(change.instance());
        counters.updatesIssued++;
    }

    private void delete(Change.Delete change) {
        EntityMetadata metadata = change.entity();
        DeleteStatement statement = sqlGenerator.delete(metadata, change.id(), change.version());
        int affected = executeUpdate(statement, "delete " + metadata.describe());
        if (affected == 0 && statement.versionGuarded()) {
            throw new OptimisticLockException("Delete of " + metadata.describe() + " with id "
                    + change.id() + " affected no rows: it was modified or deleted by another transaction",
                    change.id());
        }
        context.remove(metadata.type(), change.id());
        counters.deletesIssued++;
    }

    private int executeUpdate(io.microorm.sql.SqlStatement statement, String description) {
        try (PreparedStatement prepared = connections.connection().prepareStatement(statement.sql())) {
            ParameterBinder.bind(prepared, statement.parameters());
            log.debug("{} {}", statement.sql(), statement.parameters());
            return prepared.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Cannot " + description, e);
        }
    }

    /**
     * Publishes the incremented version on the instance, but only once the UPDATE succeeded.
     *
     * @param metadata metadata of the entity
     * @param change   the executed change
     */
    private void applyNewVersion(EntityMetadata metadata, Change.Update change) {
        metadata.version().ifPresent(field -> versionColumn(field, change.columns())
                .ifPresent(column -> field.setValue(change.instance(), column.value())));
    }

    private Optional<ColumnValue> versionColumn(FieldMetadata field, List<ColumnValue> columns) {
        List<ColumnValue> matching = new ArrayList<>();
        for (ColumnValue column : columns) {
            if (column.field().equals(field)) {
                matching.add(column);
            }
        }
        return matching.stream().findFirst();
    }
}