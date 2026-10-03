package io.microorm.session;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The first level cache: the set of entities a session manages.
 *
 * <p>Two guarantees make it the reason an ORM can offer identity semantics:
 *
 * <ul>
 *   <li>within one session, one database row is represented by exactly one Java object, so a change
 *       made through one reference is visible through all of them;</li>
 *   <li>a lookup that is already cached performs no SQL at all.</li>
 * </ul>
 *
 * <p>The nested map (entity type, then identifier) is chosen over a flat map keyed by a composite
 * value because it avoids allocating a key object per lookup and lets a whole entity type be evicted
 * in O(1). Identifier maps are ordered so that {@link #entities()} returns instances in the order
 * they were added, which makes the flush order deterministic and therefore reproducible in tests.
 *
 * <p>Instances are not thread safe; a session is not either, so no synchronisation is needed here.
 */
public final class PersistenceContext {

    private final Map<Class<?>, Map<Object, Object>> byType = new LinkedHashMap<>();

    /**
     * Registers an instance under its identifier.
     *
     * @param type   entity class
     * @param id     identifier value
     * @param entity managed instance
     * @return the instance that was registered before, empty when the row was not cached yet
     */
    public Optional<Object> put(Class<?> type, Object id, Object entity) {
        return Optional.ofNullable(byType.computeIfAbsent(type, ignored -> new LinkedHashMap<>())
                .put(id, entity));
    }

    /**
     * @param type entity class
     * @param id   identifier value
     * @return the cached instance, empty when the row is not managed
     */
    public Optional<Object> get(Class<?> type, Object id) {
        Map<Object, Object> entities = byType.get(type);
        return entities == null ? Optional.empty() : Optional.ofNullable(entities.get(id));
    }

    /**
     * @param type entity class
     * @return {@code true} when at least one instance of the type is managed
     */
    public boolean contains(Class<?> type) {
        Map<Object, Object> entities = byType.get(type);
        return entities != null && !entities.isEmpty();
    }

    /**
     * Evicts one row.
     *
     * @param type entity class
     * @param id   identifier value
     * @return the evicted instance, empty when the row was not cached
     */
    public Optional<Object> remove(Class<?> type, Object id) {
        Map<Object, Object> entities = byType.get(type);
        if (entities == null) {
            return Optional.empty();
        }
        Object removed = entities.remove(id);
        if (entities.isEmpty()) {
            byType.remove(type);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * @param type entity class
     * @return every managed instance of that type, in the order they were added
     */
    public Collection<Object> entities(Class<?> type) {
        Map<Object, Object> entities = byType.get(type);
        return entities == null ? java.util.List.of() : java.util.List.copyOf(entities.values());
    }

    /** @return every managed instance of every type, in insertion order */
    public Collection<Object> entities() {
        return byType.values().stream().flatMap(entities -> entities.values().stream()).toList();
    }

    /** @return entity classes with at least one managed instance */
    public Collection<Class<?>> managedTypes() {
        return java.util.List.copyOf(byType.keySet());
    }

    /** @return number of managed instances */
    public int size() {
        return byType.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * @param type entity class
     * @return {@code true} when the type has no managed instance
     */
    public boolean isEmpty(Class<?> type) {
        return !contains(type);
    }

    /** Drops every managed instance; used by {@code Session.clear()}. */
    public void clear() {
        byType.clear();
    }
}