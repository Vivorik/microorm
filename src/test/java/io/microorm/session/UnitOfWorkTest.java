package io.microorm.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.MetadataRegistry;
import io.microorm.sql.ColumnValue;
import io.microorm.support.TestEntities;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dirty checking is verified here without a database: the unit of work only decides <em>which</em>
 * statements have to be issued, and that decision is the interesting part.
 */
class UnitOfWorkTest {

    private final MetadataRegistry registry = new MetadataRegistry();
    private final PersistenceContext context = new PersistenceContext();
    private final UnitOfWork unitOfWork = new UnitOfWork(context, registry);

    private TestEntities.User ann;

    @BeforeEach
    void setUp() {
        ann = managed(new TestEntities.User("ann@example.com", "Ann", 30, true));
    }

    /** Loads an entity the way the session would: snapshot taken at load time. */
    private TestEntities.User managed(TestEntities.User user) {
        user.setId(1L);
        context.put(TestEntities.User.class, 1L, user);
        unitOfWork.register(user);
        return user;
    }

    private Optional<Change.Update> updateFor(TestEntities.User user) {
        return unitOfWork.pendingChanges().stream()
                .filter(Change.Update.class::isInstance)
                .map(Change.Update.class::cast)
                .findFirst();
    }

    @Test
    @DisplayName("an untouched entity produces no work at all")
    void nothingChanged() {
        assertThat(unitOfWork.pendingChanges()).isEmpty();
        assertThat(unitOfWork.hasPendingWork()).isFalse();
    }

    @Test
    @DisplayName("changing one field produces an UPDATE with that column only")
    void detectsSingleChangedField() {
        ann.setName("Anna");

        Change.Update update = updateFor(ann).orElseThrow();

        assertThat(update.columns()).extracting(column -> column.field().column())
                .containsExactly("name", "version");
        assertThat(update.columns().get(0).value()).isEqualTo("Anna");
        assertThat(update.columns().get(0).columnType()).isEqualTo(String.class);
        assertThat(update.id()).isEqualTo(1L);
        assertThat(unitOfWork.hasPendingWork()).isTrue();
    }

    @Test
    @DisplayName("setting a field back to its original value is not a change")
    void ignoresRevertedChanges() {
        ann.setName("Changed");
        ann.setName("Ann");

        assertThat(unitOfWork.pendingChanges()).isEmpty();
    }

    @Test
    @DisplayName("a column set to null after a flush is written as a null parameter")
    void detectsNullTransitions() {
        ann.setBio("hello");
        assertThat(updateFor(ann).orElseThrow().columns().get(0))
                .satisfies(column -> {
                    assertThat(column.field().column()).isEqualTo("bio");
                    assertThat(column.value()).isEqualTo("hello");
                });

        // Compare against a fresh snapshot, otherwise going back to the loaded value is - correctly -
        // not a change at all.
        unitOfWork.refreshSnapshot(ann);
        ann.setBio(null);

        assertThat(updateFor(ann).orElseThrow().columns().get(0).value()).isNull();
    }

    @Test
    @DisplayName("several changed fields end up in one UPDATE")
    void detectsMultipleChangedFields() {
        ann.setName("Anna");
        ann.setAge(31);

        List<ColumnValue> columns = updateFor(ann).orElseThrow().columns();

        assertThat(columns).extracting(column -> column.field().column())
                .containsExactly("name", "age", "version");
    }

    @Test
    @DisplayName("the version column travels with the UPDATE and is incremented from the loaded value")
    void incrementsVersion() {
        ann.setName("Anna");

        Change.Update update = updateFor(ann).orElseThrow();

        assertThat(update.version()).contains(0);
        assertThat(update.columns()).extracting(column -> column.field().column())
                .containsExactly("name", "version");
        assertThat(update.columns().get(1).value()).isEqualTo(1);
        assertThat(update.columns().get(1).value()).isInstanceOf(Integer.class);
    }

    @Test
    @DisplayName("a refreshed snapshot stops the same change from being written twice")
    void refreshSnapshotStopsDuplicateUpdate() {
        ann.setName("Anna");
        assertThat(unitOfWork.pendingChanges()).hasSize(1);

        unitOfWork.refreshSnapshot(ann);

        assertThat(unitOfWork.pendingChanges()).isEmpty();
        assertThat(unitOfWork.hasPendingWork()).isFalse();
    }

    @Test
    @DisplayName("refreshSnapshot publishes the incremented version for the next update")
    void refreshSnapshotKeepsNewVersion() {
        ann.setName("Anna");
        unitOfWork.pendingChanges().stream()
                .filter(Change.Update.class::isInstance)
                .map(Change.Update.class::cast)
                .findFirst()
                .orElseThrow()
                .columns().stream()
                .filter(column -> column.field().version())
                .findFirst()
                .orElseThrow()
                .field()
                .setValue(ann, 1);
        unitOfWork.refreshSnapshot(ann);

        ann.setName("Anna again");

        assertThat(updateFor(ann).orElseThrow().version()).contains(1);
    }

    @Test
    @DisplayName("a new entity is inserted with the insertable columns and without a generated id")
    void buildsInsert() {
        TestEntities.User fresh = new TestEntities.User("c@example.com", "Cid", 20, false);
        unitOfWork.scheduleInsert(fresh);

        Change.Insert insert = unitOfWork.pendingChanges().stream()
                .filter(Change.Insert.class::isInstance)
                .map(Change.Insert.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(insert.columns()).extracting(column -> column.field().column())
                .containsExactly("email", "name", "age", "active", "bio", "version");
        assertThat(unitOfWork.isNew(fresh)).isTrue();
        assertThat(unitOfWork.hasPendingWork()).isTrue();
    }

    @Test
    @DisplayName("an assigned identifier is included in the INSERT columns")
    void includesAssignedIdInInsert() {
        TestEntities.Order order = new TestEntities.Order("book", new BigDecimal("10.00"));
        order.setId(42L);
        unitOfWork.scheduleInsert(order);

        Change.Insert insert = unitOfWork.pendingChanges().stream()
                .filter(Change.Insert.class::isInstance)
                .map(Change.Insert.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(insert.columns()).extracting(column -> column.field().column())
                .containsExactly("id", "user_id", "description", "amount", "version");
        assertThat(insert.columns().get(0).value()).isEqualTo(42L);
    }

    @Test
    @DisplayName("an association is written as the identifier of the referenced entity")
    void writesForeignKey() {
        TestEntities.Order order = new TestEntities.Order("book", new BigDecimal("10.00"));
        order.setUser(ann);
        unitOfWork.scheduleInsert(order);

        ColumnValue userId = unitOfWork.pendingChanges().stream()
                .filter(Change.Insert.class::isInstance)
                .map(Change.Insert.class::cast)
                .findFirst()
                .orElseThrow()
                .columns().stream()
                .filter(column -> column.field().column().equals("user_id"))
                .findFirst()
                .orElseThrow();

        assertThat(userId.value()).isEqualTo(1L);
        assertThat(userId.columnType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("a null association is written as a null foreign key of the referenced type")
    void writesNullForeignKey() {
        TestEntities.Order order = new TestEntities.Order("book", new BigDecimal("10.00"));
        order.setUser(null);
        unitOfWork.scheduleInsert(order);

        ColumnValue userId = unitOfWork.pendingChanges().stream()
                .filter(Change.Insert.class::isInstance)
                .map(Change.Insert.class::cast)
                .findFirst()
                .orElseThrow()
                .columns().stream()
                .filter(column -> column.field().column().equals("user_id"))
                .findFirst()
                .orElseThrow();

        assertThat(userId.value()).isNull();
        assertThat(userId.columnType()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("removal becomes a version guarded DELETE")
    void buildsDelete() {
        unitOfWork.scheduleRemoval(ann);

        Change.Delete delete = unitOfWork.pendingChanges().stream()
                .filter(Change.Delete.class::isInstance)
                .map(Change.Delete.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(delete.id()).isEqualTo(1L);
        assertThat(delete.version()).contains(0);
        assertThat(unitOfWork.isRemoved(ann)).isTrue();
    }

    @Test
    @DisplayName("a cancelled removal is forgotten again")
    void cancelsRemoval() {
        unitOfWork.scheduleRemoval(ann);
        unitOfWork.cancelRemoval(ann);

        assertThat(unitOfWork.isRemoved(ann)).isFalse();
        assertThat(unitOfWork.pendingChanges()).isEmpty();
    }

    @Test
    @DisplayName("changes are ordered deletes, updates, inserts")
    void ordersChanges() {
        TestEntities.Order order = new TestEntities.Order("book", new BigDecimal("10.00"));
        order.setId(5L);
        context.put(TestEntities.Order.class, 5L, order);
        unitOfWork.register(order);

        ann.setName("Anna");
        unitOfWork.scheduleInsert(new TestEntities.User("d@example.com", "Dee", 22, true));
        unitOfWork.scheduleRemoval(order);

        assertThat(unitOfWork.pendingChanges()).extracting(change -> change.getClass().getSimpleName())
                .containsExactly("Delete", "Update", "Insert");
    }

    @Test
    @DisplayName("clear forgets every tracked instance")
    void clearsTrackedState() {
        unitOfWork.scheduleRemoval(ann);

        unitOfWork.clear();

        assertThat(unitOfWork.trackedEntities()).isZero();
        assertThat(unitOfWork.pendingChanges()).isEmpty();
        assertThat(unitOfWork.isRemoved(ann)).isFalse();
    }

    @Test
    @DisplayName("columns that are not updatable never appear in an UPDATE")
    void ignoresNonUpdatableColumns() {
        ann.setCreatedAt(java.time.LocalDateTime.now());

        assertThat(unitOfWork.pendingChanges()).isEmpty();
    }

    @Test
    @DisplayName("in-place changes of a byte array column are detected")
    void detectsArrayMutation() {
        TestEntities.Blob blob = new TestEntities.Blob();
        blob.setId(1L);
        blob.setContent(new byte[]{1, 2, 3});
        context.put(TestEntities.Blob.class, 1L, blob);
        unitOfWork.register(blob);

        assertThat(unitOfWork.pendingChanges()).isEmpty();

        blob.getContent()[0] = 9;

        assertThat(unitOfWork.pendingChanges()).hasSize(1);
        assertThat(((Change.Update) unitOfWork.pendingChanges().get(0)).columns().get(0).field().column())
                .isEqualTo("content");
    }

    @Test
    @DisplayName("metadata of the tracked entity is resolved from its class")
    void resolvesMetadata() {
        EntityMetadata metadata = registry.metadataFor(TestEntities.User.class);

        assertThat(metadata.tableName()).isEqualTo("users");
        assertThat(unitOfWork.trackedEntities()).isEqualTo(1);
    }
}