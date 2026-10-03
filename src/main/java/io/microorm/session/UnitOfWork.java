package io.microorm.session;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import io.microorm.metadata.MetadataRegistry;
import io.microorm.sql.ColumnValue;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The unit of work: tracks what has to be written and, above all, <em>what changed</em>.
 *
 * <p>Dirty checking works with snapshots. Every managed entity is registered with a copy of its column
 * values taken when it was loaded or persisted; at flush time the current values are compared against
 * that snapshot and only the columns that actually differ end up in the UPDATE. That is the mechanism
 * Hibernate implements with its {@code PropertyAccessor}, and it is the reason a dirty UPDATE touches
 * one column instead of the whole row.
 *
 * <p>This class performs no SQL: it produces {@link Change}s. Keeping it free of JDBC is what makes
 * the interesting part - "which columns changed" - testable without a database.
 */
public final class UnitOfWork {

    private static final Logger log = LoggerFactory.getLogger(UnitOfWork.class);

    private final MetadataRegistry registry;
    private final PersistenceContext context;
    private final FieldSnapshot snapshots;

    /** Keyed by instance identity: two equal-but-distinct objects are managed separately. */
    private final Map<Object, Map<FieldMetadata, Object>> captured = new IdentityHashMap<>();

    private final Map<Object, EntityStatus> statuses = new IdentityHashMap<>();

    public UnitOfWork(PersistenceContext context, MetadataRegistry registry) {
        this.context = context;
        this.registry = registry;
        this.snapshots = new FieldSnapshot(registry);
    }

    /**
     * Takes a snapshot of a newly loaded or freshly persisted entity.
     *
     * @param entity managed instance
     */
    public void register(Object entity) {
        EntityMetadata metadata = metadataOf(entity);
        captured.put(entity, snapshots.capture(metadata, entity));
        statuses.put(entity, EntityStatus.MANAGED);
    }

    /**
     * Schedules an entity for INSERT.
     *
     * @param entity transient instance
     */
    public void scheduleInsert(Object entity) {
        statuses.put(entity, EntityStatus.NEW);
        captured.put(entity, Map.of());
        log.debug("Registered {} as new", metadataOf(entity).describe());
    }

    /**
     * Schedules an entity for DELETE.
     *
     * @param entity managed instance
     */
    public void scheduleRemoval(Object entity) {
        statuses.put(entity, EntityStatus.REMOVED);
    }

    /**
     * Reinstates a removed entity that is still in the persistence context.
     *
     * @param entity managed instance
     */
    public void cancelRemoval(Object entity) {
        if (statuses.get(entity) == EntityStatus.REMOVED) {
            statuses.put(entity, EntityStatus.MANAGED);
        }
    }

    /**
     * @return the entities scheduled for INSERT, in scheduling order
     */
    public List<Object> scheduledInserts() {
        return managedInstances().stream()
                .filter(entity -> statuses.getOrDefault(entity, EntityStatus.MANAGED) == EntityStatus.NEW)
                .toList();
    }

    /**
     * @param entity instance to check
     * @return {@code true} when the entity is known to be new
     */
    public boolean isNew(Object entity) {
        return statuses.get(entity) == EntityStatus.NEW;
    }

    /**
     * @param entity instance to check
     * @return {@code true} when the entity is scheduled for deletion
     */
    public boolean isRemoved(Object entity) {
        return statuses.get(entity) == EntityStatus.REMOVED;
    }

    /**
     * Refreshes the snapshot after a successful write, so the same change is not written twice.
     *
     * @param entity instance that was written
     */
    public void refreshSnapshot(Object entity) {
        EntityMetadata metadata = metadataOf(entity);
        captured.put(entity, snapshots.capture(metadata, entity));
        statuses.put(entity, EntityStatus.MANAGED);
    }

    /**
     * Computes everything that has to be written.
     *
     * @return changes in a deterministic order: deletions, then updates, then inserts
     */
    public List<Change> pendingChanges() {
        List<Change> deletes = new ArrayList<>();
        List<Change> updates = new ArrayList<>();
        List<Change> inserts = new ArrayList<>();
        for (Object entity : managedInstances()) {
            EntityMetadata metadata = metadataOf(entity);
            switch (statuses.getOrDefault(entity, EntityStatus.MANAGED)) {
                case REMOVED -> deletes.add(deleteChange(metadata, entity));
                case NEW -> inserts.add(insertChange(metadata, entity));
                case MANAGED -> updateChange(metadata, entity).ifPresent(updates::add);
            }
        }
        List<Change> changes = new ArrayList<>(deletes.size() + updates.size() + inserts.size());
        changes.addAll(deletes);
        changes.addAll(updates);
        changes.addAll(inserts);
        return changes;
    }

    /**
     * @return {@code true} when a flush would issue at least one statement
     */
    public boolean hasPendingWork() {
        for (Object entity : managedInstances()) {
            EntityStatus status = statuses.getOrDefault(entity, EntityStatus.MANAGED);
            if (status == EntityStatus.NEW || status == EntityStatus.REMOVED) {
                return true;
            }
            if (status == EntityStatus.MANAGED && metadataOf(entity).identifier().getValue(entity) != null
                    && updateChange(metadataOf(entity), entity).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Forgets every tracked instance; used by {@code Session.clear()}. */
    public void clear() {
        captured.clear();
        statuses.clear();
    }

    /** @return number of tracked instances, exposed for tests */
    public int trackedEntities() {
        return captured.size();
    }

    private Change deleteChange(EntityMetadata metadata, Object entity) {
        Optional<Object> version = metadata.version()
                .map(field -> field.getValue(entity));
        return new Change.Delete(metadata, entity, metadata.identifier().getValue(entity),
                version.filter(java.util.Objects::nonNull));
    }

    private Change insertChange(EntityMetadata metadata, Object entity) {
        List<ColumnValue> columns = new ArrayList<>();
        Object id = metadata.identifier().getValue(entity);
        if (id != null) {
            columns.add(ColumnValue.of(metadata.identifier(), id));
        }
        for (FieldMetadata field : metadata.insertableFields()) {
            columns.add(snapshots.columnValue(field, entity));
        }
        return new Change.Insert(metadata, entity, columns);
    }

    private Optional<Change> updateChange(EntityMetadata metadata, Object entity) {
        Map<FieldMetadata, Object> snapshot = captured.get(entity);
        if (snapshot == null) {
            return Optional.empty();
        }
        List<ColumnValue> changed = snapshots.changedColumns(metadata, entity, snapshot);
        if (changed.isEmpty()) {
            return Optional.empty();
        }
        Optional<Object> expectedVersion = Optional.empty();
        FieldMetadata versionField = metadata.version().orElse(null);
        if (versionField != null) {
            Object loadedVersion = snapshot.get(versionField);
            if (loadedVersion != null) {
                expectedVersion = Optional.of(loadedVersion);
                // NOTE: the incremented version travels in the same UPDATE and is derived from the
                // loaded value, so two sessions cannot produce the same version number.
                changed.add(new ColumnValue(versionField,
                        FieldSnapshot.increment(loadedVersion, versionField.javaType()),
                        versionField.javaType()));
            }
        }
        return Optional.of(new Change.Update(metadata, entity, changed, expectedVersion));
    }

    private EntityMetadata metadataOf(Object entity) {
        return registry.metadataFor(entity.getClass());
    }

    /**
     * @return every tracked instance: those in the persistence context plus those whose identifier has
     *         not been assigned yet, in a stable order so that flushes are reproducible
     */
    private List<Object> managedInstances() {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object> instances = new ArrayList<>();
        for (Object entity : context.entities()) {
            seen.put(entity, Boolean.TRUE);
            instances.add(entity);
        }
        for (Object entity : captured.keySet()) {
            if (!seen.containsKey(entity)) {
                instances.add(entity);
            }
        }
        return instances;
    }

    /** Lifecycle of a managed instance inside a session. */
    private enum EntityStatus {
        /** Loaded from the database or persisted; changes are detected by comparison. */
        MANAGED,
        /** Scheduled for INSERT at the next flush. */
        NEW,
        /** Scheduled for DELETE at the next flush. */
        REMOVED
    }
}