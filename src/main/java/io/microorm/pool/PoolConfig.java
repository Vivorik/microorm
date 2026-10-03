package io.microorm.pool;

import io.microorm.exception.ConnectionPoolException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration of a {@link ConnectionPool}.
 *
 * <p>All timeouts are expressed as {@link Duration} and are validated eagerly, so a pool can never
 * be configured into a state where a caller waits forever.
 *
 * @param minSize          connections created when the pool starts
 * @param maxSize          upper bound of simultaneously open physical connections
 * @param idleTimeout      how long a connection may sit unused before it is closed
 * @param maxLifetime      hard upper bound on the age of a physical connection
 * @param connectionTimeout how long a caller may wait for a free connection
 * @param validationQuery  statement used to check whether a connection is still usable
 * @param validateOnBorrow whether reused connections are validated when they are borrowed
 * @param validateOnReturn whether connections are validated when they are returned
 * @param clock            time source; tests inject a mutable clock to exercise expiry
 */
public record PoolConfig(
        int minSize,
        int maxSize,
        Duration idleTimeout,
        Duration maxLifetime,
        Duration connectionTimeout,
        String validationQuery,
        boolean validateOnBorrow,
        boolean validateOnReturn,
        Clock clock) {

    public PoolConfig {
        if (minSize < 0) {
            throw new ConnectionPoolException("minSize must not be negative but was " + minSize);
        }
        if (maxSize < 1) {
            throw new ConnectionPoolException("maxSize must be at least 1 but was " + maxSize);
        }
        if (minSize > maxSize) {
            throw new ConnectionPoolException(
                    "minSize (" + minSize + ") must not exceed maxSize (" + maxSize + ")");
        }
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new ConnectionPoolException("idleTimeout must be positive but was " + idleTimeout);
        }
        if (maxLifetime.isNegative() || maxLifetime.isZero()) {
            throw new ConnectionPoolException("maxLifetime must be positive but was " + maxLifetime);
        }
        if (connectionTimeout.isNegative()) {
            throw new ConnectionPoolException("connectionTimeout must not be negative but was " + connectionTimeout);
        }
        if (Objects.requireNonNull(validationQuery, "validationQuery").isBlank()) {
            throw new ConnectionPoolException("validationQuery must not be blank");
        }
        Objects.requireNonNull(clock, "clock");
    }

    /**
     * @return a builder pre-filled with sensible defaults for a small OLTP service
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link PoolConfig}. */
    public static final class Builder {

        private int minSize = 1;
        private int maxSize = 10;
        private Duration idleTimeout = Duration.ofMinutes(5);
        private Duration maxLifetime = Duration.ofMinutes(30);
        private Duration connectionTimeout = Duration.ofSeconds(5);
        private String validationQuery = "SELECT 1";
        private boolean validateOnBorrow = true;
        private boolean validateOnReturn = true;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /** @param minSize connections created at startup */
        public Builder minSize(int minSize) {
            this.minSize = minSize;
            return this;
        }

        /** @param maxSize upper bound of open connections */
        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /** @param idleTimeout how long an unused connection survives */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        /** @param maxLifetime hard upper bound on connection age */
        public Builder maxLifetime(Duration maxLifetime) {
            this.maxLifetime = maxLifetime;
            return this;
        }

        /** @param connectionTimeout how long a caller waits for a free connection */
        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        /** @param validationQuery statement used as a health check */
        public Builder validationQuery(String validationQuery) {
            this.validationQuery = validationQuery;
            return this;
        }

        /** @param validateOnBorrow validate a reused connection before handing it out */
        public Builder validateOnBorrow(boolean validateOnBorrow) {
            this.validateOnBorrow = validateOnBorrow;
            return this;
        }

        /** @param validateOnReturn validate a connection before putting it back into the pool */
        public Builder validateOnReturn(boolean validateOnReturn) {
            this.validateOnReturn = validateOnReturn;
            return this;
        }

        /** @param clock time source, injectable for deterministic expiry tests */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** @return validated configuration */
        public PoolConfig build() {
            return new PoolConfig(minSize, maxSize, idleTimeout, maxLifetime, connectionTimeout,
                    validationQuery, validateOnBorrow, validateOnReturn, clock);
        }
    }
}