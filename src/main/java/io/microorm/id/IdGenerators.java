package io.microorm.id;

import io.microorm.annotation.GenerationType;
import io.microorm.metadata.EntityMetadata;
import io.microorm.sql.SqlGenerator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses the {@link IdGenerator} of an entity, following the Strategy pattern.
 *
 * <p>Generators are stateless, so one instance per strategy is shared for the whole factory and the
 * per-entity choice is cached instead of being recomputed on every insert.
 */
public final class IdGenerators {

    private final SequenceRegistry sequences = new SequenceRegistry();
    private final SequenceIdGenerator sequenceGenerator;
    private final AutoIdGenerator autoGenerator;
    private final IdentityIdGenerator identityGenerator = new IdentityIdGenerator();
    private final Map<Class<?>, IdGenerator> cache = new ConcurrentHashMap<>();

    public IdGenerators(SqlGenerator sqlGenerator) {
        this.sequenceGenerator = new SequenceIdGenerator(sqlGenerator);
        this.autoGenerator = new AutoIdGenerator(sequences, sequenceGenerator);
    }

    /**
     * @param entity metadata of the entity being persisted
     * @return the generator for its {@code @GeneratedValue} strategy
     */
    public IdGenerator forEntity(EntityMetadata entity) {
        return cache.computeIfAbsent(entity.type(), type -> switch (entity.identifier().generation()
                .orElse(GenerationType.AUTO)) {
            case IDENTITY -> identityGenerator;
            case SEQUENCE -> sequenceGenerator;
            case AUTO -> autoGenerator;
        });
    }

    /**
     * @return the sequence lookup cache, so that a caller can reset it after a schema migration
     */
    public SequenceRegistry sequences() {
        return sequences;
    }
}
