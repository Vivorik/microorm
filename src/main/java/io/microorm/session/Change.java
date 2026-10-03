package io.microorm.session;

import io.microorm.metadata.EntityMetadata;
import io.microorm.sql.ColumnValue;
import java.util.List;
import java.util.Optional;

/**
 * One pending write, ready to be turned into SQL.
 *
 * <p>Sealed because a flush is exactly an exhaustive {@code switch} over three operations, and a new
 * operation should not compile silently past that switch.
 */
public sealed interface Change {

    /** @return metadata of the entity being written */
    EntityMetadata entity();

    /** @return the managed instance the change was derived from */
    Object instance();

    /** @return identifier of the instance */
    Object id();

    /** A new row. */
    record Insert(EntityMetadata entity, Object instance, List<ColumnValue> columns) implements Change {

        public Insert {
            columns = List.copyOf(columns);
        }

        @Override
        public Object id() {
            return entity.identifier().getValue(instance);
        }
    }

    /** A row with changed columns; the UPDATE touches nothing else. */
    record Update(EntityMetadata entity, Object instance, List<ColumnValue> columns, Optional<Object> version)
            implements Change {

        public Update {
            columns = List.copyOf(columns);
        }

        @Override
        public Object id() {
            return entity.identifier().getValue(instance);
        }
    }

    /** A row to remove, optionally guarded by its version. */
    record Delete(EntityMetadata entity, Object instance, Object id, Optional<Object> version) implements Change {
    }
}
