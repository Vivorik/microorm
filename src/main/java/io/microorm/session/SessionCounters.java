package io.microorm.session;

/**
 * Mutable counters behind the immutable {@link SessionStatistics}.
 *
 * <p>Package private on purpose: the counters are an implementation detail of a session, and only the
 * session exposes them.
 */
final class SessionCounters {

    int findsIssued;
    int rowsLoaded;
    int cacheHits;
    int insertsIssued;
    int updatesIssued;
    int deletesIssued;
    int flushes;

    /** @return immutable snapshot of the counters as they are now */
    SessionStatistics snapshot() {
        return new SessionStatistics(findsIssued, rowsLoaded, cacheHits,
                insertsIssued, updatesIssued, deletesIssued, flushes);
    }
}
