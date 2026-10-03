package io.microorm.session;

import io.microorm.exception.LazyInitializationException;
import io.microorm.exception.PersistenceException;
import io.microorm.metadata.AssociationMetadata;
import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import io.microorm.proxy.LazyProxyFactory;
import io.microorm.sql.EntityRowMapper;
import io.microorm.sql.ParameterBinder;
import io.microorm.sql.SelectStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes SELECT statements and turns result rows into managed entities.
 *
 * <p>Loading an entity is more than reading a row: foreign keys have to become entities or lazy
 * proxies, and the row has to enter the persistence context. That is what happens here, which keeps
 * {@link SessionImpl} about lifecycle rather than about JDBC.
 *
 * <p>When a row arrives for an entity that is already managed, the <em>managed instance wins</em>: the
 * loaded values are copied onto it and it is returned. Without that rule, {@code find} could hand out
 * a second object for the same row and dirty checking would only ever see the newer one.
 */
final class RowLoader {

    private static final Logger log = LoggerFactory.getLogger(RowLoader.class);

    private final io.microorm.metadata.MetadataRegistry registry;
    private final io.microorm.sql.SqlGenerator sqlGenerator;
    private final PersistenceContext context;
    private final UnitOfWork unitOfWork;
    private final LazyProxyFactory proxyFactory;
    private final SessionCounters counters;
    private final SqlConnectionProvider connections;
    private final Supplier<Boolean> sessionOpen;

    RowLoader(
            io.microorm.metadata.MetadataRegistry registry,
            io.microorm.sql.SqlGenerator sqlGenerator,
            PersistenceContext context,
            UnitOfWork unitOfWork,
            LazyProxyFactory proxyFactory,
            SessionCounters counters,
            SqlConnectionProvider connections,
            Supplier<Boolean> sessionOpen) {
        this.registry = registry;
        this.sqlGenerator = sqlGenerator;
        this.context = context;
        this.unitOfWork = unitOfWork;
        this.proxyFactory = proxyFactory;
        this.counters = counters;
        this.connections = connections;
        this.sessionOpen = sessionOpen;
    }

    /**
     * Runs a SELECT and maps every row.
     *
     * @param statement prepared SELECT
     * @return the managed instances, in result order
     * @throws PersistenceException when the statement fails
     */
    List<Object> load(SelectStatement statement) {
        List<Object> entities = new ArrayList<>();
        try (PreparedStatement prepared = connections.connection().prepareStatement(statement.sql())) {
            ParameterBinder.bind(prepared, statement.parameters());
            log.debug("{} {}", statement.sql(), statement.parameters());
            try (ResultSet rows = prepared.executeQuery()) {
                while (rows.next()) {
                    entities.add(mapRow(statement.entity(), rows));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Cannot query " + statement.entity().describe(), e);
        }
        counters.findsIssued++;
        counters.rowsLoaded += entities.size();
        return entities;
    }

    /**
     * Loads one row by identifier.
     *
     * @param metadata metadata of the entity
     * @param id       identifier
     * @return the instance, or {@code null} when there is no such row
     */
    Object find(EntityMetadata metadata, Object id) {
        Optional<Object> managed = context.get(metadata.type(), id);
        if (managed.isPresent()) {
            counters.cacheHits++;
            return managed.get();
        }
        SelectStatement statement = sqlGenerator.selectById(metadata, id);
        List<Object> rows = load(statement);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Loads the instance behind a lazy proxy.
     *
     * @param target metadata of the proxied entity
     * @param id     identifier of the proxy
     * @return the real instance
     * @throws LazyInitializationException when the session was closed after the proxy was created
     * @throws io.microorm.exception.EntityNotFoundException when the row disappeared
     */
    Object loadReference(EntityMetadata target, Object id) {
        if (!sessionOpen.get()) {
            throw new LazyInitializationException("Cannot load " + target.describe() + " with id " + id
                    + ": the session that created the lazy proxy is already closed");
        }
        Object instance = find(target, id);
        if (instance == null) {
            throw new io.microorm.exception.EntityNotFoundException(target.type(), id);
        }
        return instance;
    }

    private Object mapRow(EntityMetadata metadata, ResultSet rows) throws SQLException {
        Object entity = EntityRowMapper.map(metadata, rows, new AssociationMapper());
        Object id = metadata.identifier().getValue(entity);
        if (id == null) {
            throw new PersistenceException("Row of " + metadata.describe() + " has no identifier");
        }
        Object managed = context.get(metadata.type(), id).orElse(null);
        if (managed != null && managed != entity) {
            copyState(metadata, entity, managed);
            return managed;
        }
        context.put(metadata.type(), id, entity);
        unitOfWork.register(entity);
        return entity;
    }

    /** Copies every mapped field from one instance to another. */
    static void copyState(EntityMetadata metadata, Object from, Object to) {
        for (FieldMetadata field : metadata.fields()) {
            // NOTE: an association value may be an uninitialised proxy; copying it is harmless because
            // the unit of work reads the identifier field of a proxy directly, without loading it.
            field.setValue(to, field.getValue(from));
        }
    }

    /** Resolves the foreign keys of a loaded row into entities or lazy proxies. */
    private final class AssociationMapper implements EntityRowMapper.AssociationResolver {

        @Override
        public void resolve(FieldMetadata field, Object foreignKey, Object owner) {
            Optional<AssociationMetadata> association = field.association();
            if (association.isEmpty()) {
                return;
            }
            if (foreignKey == null) {
                field.setValue(owner, null);
                return;
            }
            EntityMetadata target = registry.metadataFor(association.get().targetType());
            Object resolved = context.get(target.type(), foreignKey).orElse(null);
            if (resolved == null && association.get().lazy()) {
                resolved = proxyFactory.createProxy(target, foreignKey,
                        proxyId -> loadReference(target, proxyId));
            } else if (resolved == null) {
                resolved = find(target, foreignKey);
            }
            field.setValue(owner, resolved);
        }

        @Override
        public Class<?> columnTypeOf(FieldMetadata field) {
            return registry.metadataFor(field.association().orElseThrow().targetType())
                    .identifier().javaType();
        }
    }
}