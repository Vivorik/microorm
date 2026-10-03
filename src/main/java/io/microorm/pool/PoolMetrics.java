package io.microorm.pool;

/**
 * Immutable snapshot of the state of a {@link ConnectionPool}.
 *
 * @param total     physical connections that currently exist
 * @param active    connections currently borrowed by application code
 * @param idle      connections available for reuse
 * @param waiting   threads currently blocked waiting for a free connection
 * @param created   physical connections created since startup
 * @param discarded physical connections closed because they were expired or broken
 */
public record PoolMetrics(int total, int active, int idle, int waiting, long created, long discarded) {

    /**
     * @param maxSize configured upper bound
     * @return {@code true} when every connection is in use
     */
    public boolean saturated(int maxSize) {
        return active >= maxSize;
    }

    @Override
    public String toString() {
        return "total=" + total + ", active=" + active + ", idle=" + idle
                + ", waiting=" + waiting + ", created=" + created + ", discarded=" + discarded;
    }
}
