package io.microorm.exception;

/**
 * Root of every exception thrown by MicroORM.
 *
 * <p>It is a {@code sealed interface} with a fixed set of permitted subtypes, so callers can
 * exhaustively {@code switch} over failure modes without a {@code default} branch. The subtypes
 * extend {@link RuntimeException}, hence the human readable text is available via
 * {@link Throwable#getMessage()}.
 */
public sealed interface MicroOrmException
        permits MappingException,
                PersistenceException,
                OptimisticLockException,
                LazyInitializationException,
                TransactionRequiredException,
                NonUniqueResultException,
                ConnectionPoolException {
}