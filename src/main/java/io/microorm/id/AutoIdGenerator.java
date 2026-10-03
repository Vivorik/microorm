package io.microorm.id;

import io.microorm.metadata.EntityMetadata;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides between a sequence and an identity column, once per entity and connection.
 *
 * <p>This is what {@code GenerationType.AUTO} means in MicroORM: if the schema has a sequence named
 * {@code <table>_id_seq}, use it, otherwise let the identity column assign the value.
 */
public final class AutoIdGenerator implements IdGenerator {

    private static final Logger log = LoggerFactory.getLogger(AutoIdGenerator.class);

    private final SequenceRegistry sequences;
    private final SequenceIdGenerator sequenceGenerator;

    public AutoIdGenerator(SequenceRegistry sequences, SequenceIdGenerator sequenceGenerator) {
        this.sequences = sequences;
        this.sequenceGenerator = sequenceGenerator;
    }

    @Override
    public Optional<Object> generate(Connection connection, EntityMetadata entity) throws SQLException {
        return delegateFor(connection, entity).generate(connection, entity);
    }

    /**
     * @param connection connection whose schema is inspected
     * @param entity     metadata of the entity
     * @return the generator that will actually produce the identifier
     */
    public IdGenerator delegateFor(Connection connection, EntityMetadata entity) {
        String sequenceName = sequenceGenerator.sequenceName(entity);
        if (sequences.exists(connection, sequenceName)) {
            log.debug("{}: AUTO resolved to the sequence {}", entity.describe(), sequenceName);
            return sequenceGenerator;
        }
        log.debug("{}: AUTO resolved to an identity column", entity.describe());
        return new IdentityIdGenerator();
    }
}
