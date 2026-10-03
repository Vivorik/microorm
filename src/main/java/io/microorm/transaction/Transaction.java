package io.microorm.transaction;

import io.microorm.exception.PersistenceException;
import io.microorm.exception.TransactionRequiredException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A transaction bound to a single JDBC connection.
 *
 * <p>Nesting is implemented with savepoints, which is what a JDBC driver supports and what JPA's
 * "nested transaction" actually is:
 *
 * <ul>
 *   <li>{@link #beginNested()} creates a savepoint;</li>
 *   <li>committing a nested transaction releases the savepoint, so its work stays pending inside the
 *       outer transaction and is committed or rolled back together with it;</li>
 *   <li>rolling back a nested transaction rolls back <em>to</em> the savepoint: the inner work is
 *       discarded while the outer transaction stays alive and usable.</li>
 * </ul>
 *
 * <p>After {@link #rollback()} the transaction is finished: further statements must not run in it,
 * so any operation raises {@link TransactionRequiredException}.
 */
public final class Transaction {

    private static final Logger log = LoggerFactory.getLogger(Transaction.class);

    private final Connection connection;
    private final IsolationLevel isolationLevel;
    private final Deque<Savepoint> savepoints = new ArrayDeque<>();

    private TransactionState state = TransactionState.ACTIVE;
    private int depth = 1;
    private int savepointCounter;

    private Transaction(Connection connection, IsolationLevel isolationLevel) {
        this.connection = connection;
        this.isolationLevel = isolationLevel;
    }

    /**
     * Starts a transaction on a connection that is in auto-commit mode.
     *
     * @param connection      JDBC connection, still in auto-commit mode
     * @param isolationLevel  isolation to request from the driver
     * @return a started transaction
     * @throws PersistenceException when the driver refuses the isolation level
     */
    public static Transaction begin(Connection connection, IsolationLevel isolationLevel) {
        try {
            // NOTE: auto-commit is switched off before the isolation level, otherwise the driver
            // would commit the implicit single statement transaction right away.
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(isolationLevel.jdbcLevel());
        } catch (SQLException e) {
            throw new PersistenceException("Cannot start a transaction with isolation "
                    + isolationLevel, e);
        }
        log.debug("Transaction started with isolation {}", isolationLevel);
        return new Transaction(connection, isolationLevel);
    }

    /** @return {@code true} while the transaction accepts statements */
    public boolean isActive() {
        return state == TransactionState.ACTIVE || state == TransactionState.ROLLBACK_ONLY;
    }

    /** @return the nesting depth, {@code 1} for a top level transaction */
    public int depth() {
        return depth;
    }

    /** @return isolation level this transaction runs with */
    public IsolationLevel isolationLevel() {
        return isolationLevel;
    }

    /** @return the JDBC connection this transaction is bound to */
    public Connection connection() {
        return connection;
    }

    /**
     * Opens a nested transaction backed by a savepoint.
     *
     * @return the created savepoint
     * @throws TransactionRequiredException when the transaction is no longer usable
     */
    public Savepoint beginNested() {
        requireActive("begin a nested transaction");
        depth++;
        Savepoint savepoint = createSavepoint();
        savepoints.push(savepoint);
        log.debug("Nested transaction started at savepoint {}", savepointCounter);
        return savepoint;
    }

    /**
     * Commits the innermost transaction.
     *
     * <p>For a nested transaction this only releases its savepoint; the work becomes part of the
     * enclosing transaction. The outermost {@code commit} is the one that reaches the database.
     *
     * @throws TransactionRequiredException when there is no active transaction
     * @throws PersistenceException        when the driver fails to commit
     */
    public void commit() {
        if (state == TransactionState.FINISHED) {
            throw new TransactionRequiredException("Cannot commit: the transaction is already finished");
        }
        if (state == TransactionState.ROLLED_BACK) {
            throw new TransactionRequiredException("Cannot commit: the transaction was rolled back");
        }
        if (depth > 1) {
            releaseSavepoint(popSavepoint());
            depth--;
            log.debug("Nested transaction committed, depth is now {}", depth);
            return;
        }
        run("commit", connection::commit);
        state = TransactionState.FINISHED;
        log.debug("Transaction committed");
    }

    /**
     * Rolls back the innermost transaction.
     *
     * <p>A nested rollback undoes only the work done since the matching savepoint; the outer
     * transaction stays active. Rolling back the outermost transaction discards everything.
     *
     * @throws TransactionRequiredException when there is no active transaction
     * @throws PersistenceException        when the driver fails to roll back
     */
    public void rollback() {
        if (state == TransactionState.FINISHED) {
            throw new TransactionRequiredException("Cannot roll back: the transaction is already committed");
        }
        if (state == TransactionState.ROLLED_BACK) {
            return;
        }
        if (depth > 1) {
            rollbackToSavepoint(popSavepoint());
            depth--;
            log.debug("Nested transaction rolled back, depth is now {}", depth);
            return;
        }
        run("rollback", connection::rollback);
        state = TransactionState.ROLLED_BACK;
        log.debug("Transaction rolled back");
    }

    /**
     * Marks the transaction as doomed, so that the session rolls it back when it closes.
     *
     * <p>Needed when a failure makes the transaction unusable without unwinding the call stack,
     * for example an optimistic lock failure in the middle of a unit of work.
     */
    public void markRollbackOnly() {
        if (state == TransactionState.ACTIVE) {
            state = TransactionState.ROLLBACK_ONLY;
        }
    }

    /** @return {@code true} when the transaction was marked as doomed */
    public boolean isRollbackOnly() {
        return state == TransactionState.ROLLBACK_ONLY;
    }

    /** @return the savepoint of the innermost nested transaction, if any */
    public Optional<Savepoint> currentSavepoint() {
        return Optional.ofNullable(savepoints.peek());
    }

    /** @return {@code true} when this is a nested transaction */
    public boolean isNested() {
        return depth > 1;
    }

    @Override
    public String toString() {
        return "Transaction{depth=" + depth + ", state=" + state + ", isolation=" + isolationLevel + '}';
    }

    private Savepoint createSavepoint() {
        return execute("createSavepoint", () -> connection.setSavepoint("microorm_sp_" + (++savepointCounter)));
    }

    private void releaseSavepoint(Savepoint savepoint) {
        run("releaseSavepoint", () -> connection.releaseSavepoint(savepoint));
    }

    private void rollbackToSavepoint(Savepoint savepoint) {
        run("rollback to savepoint", () -> connection.rollback(savepoint));
    }

    private Savepoint popSavepoint() {
        Savepoint savepoint = savepoints.poll();
        if (savepoint == null) {
            throw new TransactionRequiredException("Nesting depth and savepoint stack are out of sync");
        }
        return savepoint;
    }

    private void requireActive(String operation) {
        if (state == TransactionState.FINISHED || state == TransactionState.ROLLED_BACK) {
            throw new TransactionRequiredException(
                    "Cannot " + operation + ": the transaction is " + state.name().toLowerCase());
        }
    }

    private <T> T execute(String description, SqlSupplier<T> action) {
        try {
            return action.get();
        } catch (SQLException e) {
            throw new PersistenceException("Cannot " + description + " of a transaction", e);
        }
    }

    private void run(String description, SqlAction action) {
        execute(description, () -> {
            action.run();
            return null;
        });
    }

    /** A {@link java.util.function.Supplier} that is allowed to throw {@link SQLException}. */
    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    /** A {@link Runnable} that is allowed to throw {@link SQLException}. */
    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private enum TransactionState {
        ACTIVE,
        ROLLBACK_ONLY,
        FINISHED,
        ROLLED_BACK
    }
}