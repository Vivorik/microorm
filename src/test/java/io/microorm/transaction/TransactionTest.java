package io.microorm.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.PersistenceException;
import io.microorm.exception.TransactionRequiredException;
import io.microorm.support.FakeConnections;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private final FakeConnections.State state = new FakeConnections.State();
    private final Connection connection = FakeConnections.create(state);

    private Transaction begin() {
        return Transaction.begin(connection, IsolationLevel.READ_COMMITTED);
    }

    @Test
    @DisplayName("starting a transaction switches auto-commit off and applies the isolation level")
    void beginsTransaction() throws Exception {
        Transaction transaction = begin();

        assertThat(transaction.isActive()).isTrue();
        assertThat(transaction.depth()).isEqualTo(1);
        assertThat(transaction.isNested()).isFalse();
        assertThat(transaction.isolationLevel()).isEqualTo(IsolationLevel.READ_COMMITTED);
        assertThat(transaction.connection()).isSameAs(connection);
        assertThat(state.events())
                .containsExactly("setAutoCommit(false)", "setTransactionIsolation(2)");
        assertThat(state.isolationLevel()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    @DisplayName("committing reaches the database and finishes the transaction")
    void commits() throws Exception {
        Transaction transaction = begin();

        transaction.commit();

        assertThat(state.events()).contains("commit");
        assertThat(transaction.isActive()).isFalse();
        assertThatThrownBy(transaction::beginNested)
                .isInstanceOf(TransactionRequiredException.class)
                .hasMessageContaining("Cannot begin a nested transaction: the transaction is finished");
    }

    @Test
    @DisplayName("rolling back discards the work and finishes the transaction")
    void rollsBack() throws Exception {
        Transaction transaction = begin();

        transaction.rollback();

        assertThat(state.events()).contains("rollback");
        assertThat(transaction.isActive()).isFalse();
        assertThatThrownBy(transaction::commit)
                .isInstanceOf(TransactionRequiredException.class)
                .hasMessageContaining("was rolled back");
    }

    @Test
    @DisplayName("committing twice is refused instead of committing twice")
    void refusesDoubleCommit() throws Exception {
        Transaction transaction = begin();
        transaction.commit();

        assertThatThrownBy(transaction::commit)
                .isInstanceOf(TransactionRequiredException.class)
                .hasMessageContaining("already finished");
    }

    @Test
    @DisplayName("rolling back twice is a no-op, matching JDBC semantics")
    void toleratesDoubleRollback() throws Exception {
        Transaction transaction = begin();
        transaction.rollback();

        transaction.rollback();

        assertThat(state.events()).containsOnlyOnce("rollback");
    }

    @Test
    @DisplayName("a nested transaction commits its savepoint and leaves the work to the outer one")
    void commitsNestedTransaction() throws Exception {
        Transaction transaction = begin();

        transaction.beginNested();
        assertThat(transaction.isNested()).isTrue();
        assertThat(transaction.depth()).isEqualTo(2);
        transaction.commit();

        assertThat(state.events())
                .containsExactly("setAutoCommit(false)", "setTransactionIsolation(2)",
                        "setSavepoint(microorm_sp_1)", "releaseSavepoint(microorm_sp_1)");
        assertThat(transaction.isActive()).isTrue();

        transaction.commit();

        assertThat(state.events()).endsWith("commit");
    }

    @Test
    @DisplayName("rolling back a nested transaction keeps the outer one alive")
    void rollsBackNestedTransaction() throws Exception {
        Transaction transaction = begin();
        transaction.beginNested();

        transaction.rollback();

        assertThat(state.events())
                .containsExactly("setAutoCommit(false)", "setTransactionIsolation(2)",
                        "setSavepoint(microorm_sp_1)", "rollback(microorm_sp_1)");
        assertThat(transaction.isActive()).isTrue();
        assertThat(transaction.depth()).isEqualTo(1);
        assertThat(transaction.isNested()).isFalse();

        transaction.commit();

        assertThat(state.events()).endsWith("commit");
    }

    @Test
    @DisplayName("nested savepoints are named in order and released innermost first")
    void nestsDeeply() throws Exception {
        Transaction transaction = begin();
        Savepoint first = transaction.beginNested();
        Savepoint second = transaction.beginNested();

        assertThat(transaction.currentSavepoint()).contains(second);
        assertThat(transaction.depth()).isEqualTo(3);

        transaction.rollback();
        assertThat(transaction.currentSavepoint()).contains(first);

        transaction.rollback();
        assertThat(transaction.currentSavepoint()).isEmpty();
        assertThat(state.events()).containsSubsequence(
                "setSavepoint(microorm_sp_1)",
                "setSavepoint(microorm_sp_2)",
                "rollback(microorm_sp_2)",
                "rollback(microorm_sp_1)");
    }

    @Test
    @DisplayName("an exception in the middle of the unit of work rolls everything back")
    void rollsBackAfterFailure() throws Exception {
        Transaction transaction = begin();
        transaction.beginNested();

        assertThatThrownBy(() -> {
            transaction.beginNested();
            throw new IllegalStateException("boom");
        }).isInstanceOf(IllegalStateException.class);

        transaction.rollback();
        transaction.rollback();

        assertThat(transaction.isActive()).isTrue();
        transaction.rollback();
        assertThat(transaction.isActive()).isFalse();
        assertThat(state.events()).containsOnlyOnce("rollback");
    }

    @Test
    @DisplayName("markRollbackOnly dooms the transaction without touching the database yet")
    void marksRollbackOnly() throws Exception {
        Transaction transaction = begin();

        transaction.markRollbackOnly();

        assertThat(transaction.isRollbackOnly()).isTrue();
        assertThat(transaction.isActive()).isTrue();
        assertThat(state.events()).doesNotContain("rollback");

        transaction.rollback();

        assertThat(state.events()).contains("rollback");
    }

    @Test
    @DisplayName("a driver that refuses the isolation level is reported as PersistenceException")
    void reportsIsolationFailure() {
        state.rejectIsolationLevel();

        assertThatThrownBy(() -> Transaction.begin(connection, IsolationLevel.SERIALIZABLE))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("Cannot start a transaction with isolation SERIALIZABLE")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("every isolation level is mapped to a JDBC constant")
    void mapsIsolationLevels() throws Exception {
        assertThat(IsolationLevel.READ_UNCOMMITTED.jdbcLevel())
                .isEqualTo(Connection.TRANSACTION_READ_UNCOMMITTED);
        assertThat(IsolationLevel.READ_COMMITTED.jdbcLevel()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(IsolationLevel.REPEATABLE_READ.jdbcLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(IsolationLevel.SERIALIZABLE.jdbcLevel()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);

        for (IsolationLevel level : IsolationLevel.values()) {
            assertThat(IsolationLevel.fromJdbc(level.jdbcLevel())).isEqualTo(level);
        }
        assertThatThrownBy(() -> IsolationLevel.fromJdbc(9999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported isolation level: 9999");
    }

    @Test
    @DisplayName("toString is useful in log messages")
    void describesItself() throws Exception {
        Transaction transaction = begin();
        transaction.beginNested();

        assertThat(transaction.toString())
                .isEqualTo("Transaction{depth=2, state=ACTIVE, isolation=READ_COMMITTED}");
    }
}