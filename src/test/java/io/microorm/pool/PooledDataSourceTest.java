package io.microorm.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.support.FakeConnections;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PooledDataSourceTest {

    private final AtomicInteger creations = new AtomicInteger();
    private final List<FakeConnections.State> states = new ArrayList<>();
    private ConnectionPool pool;

    @BeforeEach
    void setUp() {
        pool = new ConnectionPool(FakeConnections.dataSource(creations, states),
                PoolConfig.builder().minSize(1).maxSize(1).build());
    }

    @Test
    @DisplayName("getConnection() returns a pooled connection that goes back on close()")
    void borrowsPooledConnection() throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            assertThat(pool.metrics().active()).isEqualTo(1);
            assertThat(connection.isClosed()).isFalse();
        }

        assertThat(pool.metrics().idle()).isEqualTo(1);
        assertThat(states.get(0).closeCount()).isZero();
    }

    @Test
    @DisplayName("credentials are rejected because they belong to the physical data source")
    void rejectsCredentials() {
        assertThatThrownBy(() -> dataSource().getConnection("user", "secret"))
                .isInstanceOf(SQLFeatureNotSupportedException.class)
                .hasMessageContaining("single credential set");
    }

    @Test
    @DisplayName("DataSource methods that make no sense for a pool report it clearly")
    void rejectsUnsupportedDataSourceFeatures() {
        DataSource dataSource = dataSource();

        assertThatThrownBy(dataSource::getLogWriter).isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(() -> dataSource.setLogWriter(null))
                .isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(() -> dataSource.setLoginTimeout(1))
                .isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(dataSource::getLoginTimeout).isInstanceOf(SQLFeatureNotSupportedException.class);
        assertThatThrownBy(dataSource::getParentLogger).isInstanceOf(SQLFeatureNotSupportedException.class);
    }

    @Test
    @DisplayName("unwrap follows the DataSource contract")
    void unwraps() throws SQLException {
        DataSource dataSource = dataSource();

        assertThat(dataSource.isWrapperFor(DataSource.class)).isTrue();
        assertThat(dataSource.isWrapperFor(Connection.class)).isFalse();
        assertThat(dataSource.unwrap(DataSource.class)).isSameAs(dataSource);
        assertThatThrownBy(() -> dataSource.unwrap(Connection.class))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Not a wrapper for java.sql.Connection");
    }

    @Test
    @DisplayName("closing the data source closes the pool")
    void closesPool() {
        PooledDataSource dataSource = new PooledDataSource(pool);
        dataSource.close();

        assertThat(states.get(0).closeCount()).isEqualTo(1);
        assertThatThrownBy(pool::borrow).isInstanceOf(io.microorm.exception.ConnectionPoolException.class);
    }

    private DataSource dataSource() {
        return new PooledDataSource(pool);
    }
}