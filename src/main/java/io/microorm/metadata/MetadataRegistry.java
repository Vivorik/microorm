package io.microorm.metadata;

import io.microorm.exception.MappingException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache of {@link EntityMetadata} keyed by entity class.
 *
 * <p>Reflection is expensive relative to a single SELECT, and metadata never changes at runtime, so
 * every entity is parsed exactly once. The cache is a {@link ConcurrentHashMap}: it is read from
 * application threads and written lazily on first use, which is precisely what it is safe for.
 *
 * <p>Entities may also be registered eagerly, which is what DDL generation needs because it has to
 * know the full set of tables up front.
 */
public final class MetadataRegistry {

    private final Map<Class<?>, EntityMetadata> cache = new ConcurrentHashMap<>();
    private final MetadataParser parser;

    public MetadataRegistry() {
        this(new MetadataParser());
    }

    public MetadataRegistry(MetadataParser parser) {
        this.parser = parser;
    }

    /**
     * Returns metadata for an entity class, parsing it on first access.
     *
     * @param type entity class annotated with {@code @Entity}
     * @return cached metadata
     * @throws MappingException when the class is not a valid entity
     */
    public EntityMetadata metadataFor(Class<?> type) {
        EntityMetadata cached = cache.get(type);
        if (cached != null) {
            return cached;
        }
        return cache.computeIfAbsent(type, parser::parse);
    }

    /**
     * Parses and caches metadata for several classes eagerly.
     *
     * <p>Useful at startup to turn mapping errors into a fail-fast boot failure.
     *
     * @param types entity classes
     * @return metadata in registration order
     */
    public List<EntityMetadata> register(Class<?>... types) {
        return List.of(types).stream().map(this::metadataFor).toList();
    }

    /** @return metadata of all classes known so far, in unspecified order */
    public Collection<EntityMetadata> knownMetadata() {
        return List.copyOf(cache.values());
    }

    /**
     * Resolves the Java type actually stored in a column.
     *
     * <p>For a scalar field that is the field type; for an association it is the type of the referenced
     * identifier, because that is what the foreign key column holds. Every piece of code that binds a
     * value needs this, and getting it wrong means binding a {@code Long} as if it were an entity.
     *
     * @param field mapped field
     * @return basic type of the column value
     */
    public Class<?> columnJavaType(FieldMetadata field) {
        return field.association()
                .<Class<?>>map(association -> metadataFor(association.targetType()).identifier().javaType())
                .orElse(field.javaType());
    }

    /**
     * @param type entity class
     * @return {@code true} when metadata has already been parsed for the class
     */
    public boolean isRegistered(Class<?> type) {
        return cache.containsKey(type);
    }

    /** @return number of cached entities, primarily for diagnostics and tests */
    public int size() {
        return cache.size();
    }

    /**
     * Removes one entry, used by tests that need a clean registry.
     *
     * @param type entity class to evict
     */
    public void evict(Class<?> type) {
        cache.remove(type);
    }

    /** @return snapshot of the cache keyed by class, ordered for stable error messages */
    public Map<Class<?>, EntityMetadata> asMap() {
        return new LinkedHashMap<>(cache);
    }
}