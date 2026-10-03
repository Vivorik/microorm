package io.microorm.id;

import io.microorm.metadata.EntityMetadata;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Strategy that resolves the identifier of a new entity.
 *
 * <p>Sealed because the three strategies of {@code @GeneratedValue} are the only ones that exist: no
 * extension hook is needed, and an unexpected implementation cannot sneak in through configuration.
 *
 * <p>The return value is what makes the two mechanisms visible to the session:
 *
 * <ul>
 *   <li>{@link Optional#empty()} - the database assigns the identifier, so the INSERT carries a
 *       {@code RETURNING} clause and the value is read back from the result;</li>
 *   <li>a value - the strategy produced it, so it is bound as an ordinary INSERT column.</li>
 * </ul>
 */
public sealed interface IdGenerator permits IdentityIdGenerator, SequenceIdGenerator, AutoIdGenerator {

    /**
     * Resolves the identifier of a new entity.
     *
     * @param connection connection of the current transaction
     * @param entity     metadata of the entity being persisted
     * @return the identifier, or empty when the database assigns it
     * @throws SQLException when the database call fails
     */
    Optional<Object> generate(Connection connection, EntityMetadata entity) throws SQLException;
}
