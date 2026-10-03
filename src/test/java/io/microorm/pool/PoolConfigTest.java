package io.microorm.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.ConnectionPoolException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PoolConfigTest {

    @Test
    @DisplayName("defaults are usable for a small OLTP service")
    void hasDefaults() {
        PoolConfig config = PoolConfig.builder().build();

        assertThat(config.minSize()).isEqualTo(1);
        assertThat(config.maxSize()).isEqualTo(10);
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.maxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(config.connectionTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.validationQuery()).isEqualTo("SELECT 1");
        assertThat(config.validateOnBorrow()).isTrue();
        assertThat(config.validateOnReturn()).isTrue();
        assertThat(config.clock()).isEqualTo(Clock.systemUTC());
    }

    @Test
    @DisplayName("every value can be overridden")
    void isFullyConfigurable() {
        Clock clock = Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC);

        PoolConfig config = PoolConfig.builder()
                .minSize(2)
                .maxSize(20)
                .idleTimeout(Duration.ofSeconds(30))
                .maxLifetime(Duration.ofMinutes(10))
                .connectionTimeout(Duration.ofSeconds(1))
                .validationQuery("SELECT 1 FROM pg_class")
                .validateOnBorrow(false)
                .validateOnReturn(false)
                .clock(clock)
                .build();

        assertThat(config.minSize()).isEqualTo(2);
        assertThat(config.maxSize()).isEqualTo(20);
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.maxLifetime()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.connectionTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(config.validationQuery()).isEqualTo("SELECT 1 FROM pg_class");
        assertThat(config.validateOnBorrow()).isFalse();
        assertThat(config.validateOnReturn()).isFalse();
        assertThat(config.clock()).isSameAs(clock);
    }

    @Test
    @DisplayName("rejects sizes that cannot work")
    void rejectsInvalidSizes() {
        assertThatThrownBy(() -> PoolConfig.builder().minSize(-1).build())
                .isInstanceOf(ConnectionPoolException.class)
                .hasMessageContaining("minSize must not be negative");
        assertThatThrownBy(() -> PoolConfig.builder().maxSize(0).build())
                .isInstanceOf(ConnectionPoolException.class)
                .hasMessageContaining("maxSize must be at least 1");
        assertThatThrownBy(() -> PoolConfig.builder().minSize(5).maxSize(2).build())
                .isInstanceOf(ConnectionPoolException.class)
                .hasMessageContaining("must not exceed maxSize");
    }

    @Test
    @DisplayName("rejects timeouts that would make callers wait forever")
    void rejectsInvalidTimeouts() {
        assertThatThrownBy(() -> PoolConfig.builder().idleTimeout(Duration.ZERO).build())
                .isInstanceOf(ConnectionPoolException.class)
                .hasMessageContaining("idleTimeout must be positive");
        assertThatThrownBy(() -> PoolConfig.builder().maxLifetime(Duration.ofSeconds(-1)).build())
                .isInstanceOf(ConnectionPoolException.class)
                .hasMessageContaining("maxLifetime must be positive");
        assertThatThrownBy(() -> PoolConfig.builder().connectionTimeout(Duration.ofSeconds(-1)).build())
                .isInstanceOf(ConnectionPoolException.class)
                .hasMessageContaining("connectionTimeout must not be negative");
    }

    @Test
    @DisplayName("a zero connection timeout is allowed: it means 'fail immediately when saturated'")
    void allowsZeroConnectionTimeout() {
        assertThat(PoolConfig.builder().connectionTimeout(Duration.ZERO).build().connectionTimeout())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("rejects a blank health check and missing collaborators")
    void rejectsInvalidValidationQuery() {
        assertThatThrownBy(() -> PoolConfig.builder().validationQuery("  ").build())
                .isInstanceOf(ConnectionPoolException.class)
                .hasMessageContaining("validationQuery must not be blank");
        assertThatThrownBy(() -> PoolConfig.builder().clock(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clock");
    }
}