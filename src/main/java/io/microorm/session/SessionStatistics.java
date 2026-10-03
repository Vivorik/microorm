package io.microorm.session;

/**
 * Counters describing the work a session performed.
 *
 * <p>These are not a monitoring feature: they exist so that a test can assert that a second
 * {@code find} did not hit the database, or that a flush issued exactly one UPDATE.
 *
 * @param findsIssued    SELECT statements executed for {@code find} and {@code findAll}
 * @param rowsLoaded     entities materialised from result sets
 * @param cacheHits      lookups answered from the persistence context
 * @param insertsIssued  INSERT statements executed
 * @param updatesIssued  UPDATE statements executed
 * @param deletesIssued  DELETE statements executed
 * @param flushes        number of times the unit of work ran
 */
public record SessionStatistics(
        int findsIssued,
        int rowsLoaded,
        int cacheHits,
        int insertsIssued,
        int updatesIssued,
        int deletesIssued,
        int flushes) {

    public SessionStatistics {
        if (findsIssued < 0 || rowsLoaded < 0 || cacheHits < 0
                || insertsIssued < 0 || updatesIssued < 0 || deletesIssued < 0 || flushes < 0) {
            throw new IllegalArgumentException("Session counters must not be negative");
        }
    }

    /** @return number of write statements issued */
    public int writes() {
        return insertsIssued + updatesIssued + deletesIssued;
    }

    @Override
    public String toString() {
        return "selects=" + findsIssued + ", rows=" + rowsLoaded + ", cacheHits=" + cacheHits
                + ", inserts=" + insertsIssued + ", updates=" + updatesIssued
                + ", deletes=" + deletesIssued + ", flushes=" + flushes;
    }
}
