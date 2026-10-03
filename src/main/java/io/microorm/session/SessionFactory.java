package io.microorm.session;

import io.microorm.id.IdGenerators;
import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.MetadataRegistry;
import io.microorm.pool.ConnectionPool;
import io.microorm.pool.PoolConfig;
import io.microorm.pool.PooledDataSource;
import io.microorm.proxy.LazyProxyFactory;
import io.microorm.schema.SchemaExporter;
import io.microorm.sql.Dialect;
import io.microorm.sql.PostgresDialect;
import io.microorm.sql.SqlGenerator;
import io.microorm.transaction.IsolationLevel;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of MicroORM: owns the {@link DataSource} and the entity metadata.
 *
 * <p>A factory is expensive to build and cheap to use, so applications create one per database and
 * keep it for their whole lifetime. Sessions are the cheap, short lived objects.
 *
 * <p>The factory also holds the collaborators that must be shared between sessions - the metadata
 * cache, the SQL generator and the id generation strategies - because all of them are stateless or
 * internally synchronised.
 */
public final class SessionFactory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionFactory.class);

    private final DataSource dataSource;
    private final ConnectionPool ownedPool;
    private final MetadataRegistry metadata;
    private final IsolationLevel isolationLevel;
    private final SqlGenerator sqlGenerator;
    private final IdGenerators idGenerators;
    private final LazyProxyFactory proxyFactory;

    private SessionFactory(Builder builder) {
        this.ownedPool = builder.pool;
        this.dataSource = builder.pool != null
                ? new PooledDataSource(builder.pool)
                : builder.dataSource;
        this.metadata = builder.metadata;
        this.isolationLevel = builder.isolationLevel;
        this.sqlGenerator = new SqlGenerator(builder.dialect);
        this.idGenerators = new IdGenerators(sqlGenerator);
        this.proxyFactory = new LazyProxyFactory();
        builder.entities.forEach(metadata::metadataFor);
    }

    /**
     * @param builder configuration
     * @return a factory ready to open sessions
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Opens a new session with an empty persistence context.
     *
     * @return a session that must be closed, typically with try-with-resources
     */
    public Session openSession() {
        return new SessionImpl(this, new PersistenceContext());
    }

    /** @return the data source sessions borrow connections from */
    public DataSource dataSource() {
        return dataSource;
    }

    /** @return the pool, empty when the factory was built on a foreign data source */
    public java.util.Optional<ConnectionPool> pool() {
        return java.util.Optional.ofNullable(ownedPool);
    }

    /**
     * @return a callback that disposes of broken connections when this factory owns a pool, and
     *         only logs them otherwise
     */
    io.microorm.transaction.ConnectionInvalidator invalidator() {
        return ownedPool != null
                ? ownedPool::invalidate
                : (connection, reason) -> log.warn(
                        "Broken connection of a foreign data source cannot be discarded: {}", reason);
    }

    /** @return the metadata cache shared by all sessions */
    public MetadataRegistry metadata() {
        return metadata;
    }

    /** @return default isolation level of new transactions */
    public IsolationLevel isolationLevel() {
        return isolationLevel;
    }

    /** @return the SQL generator shared by all sessions */
    public SqlGenerator sqlGenerator() {
        return sqlGenerator;
    }

    /** @return the identifier generation strategies shared by all sessions */
    public IdGenerators idGenerators() {
        return idGenerators;
    }

    /** @return the lazy proxy factory shared by all sessions */
    public LazyProxyFactory proxyFactory() {
        return proxyFactory;
    }

    /**
     * Parses metadata of the given entity classes eagerly, so mapping errors surface at startup.
     *
     * @param types entity classes
     */
    public void registerEntities(Class<?>... types) {
        metadata.register(types);
    }

    /**
     * @return metadata of every registered entity, in registration order
     */
    public List<EntityMetadata> knownEntities() {
        return List.copyOf(metadata.knownMetadata());
    }

    /**
     * Creates an exporter for the DDL of the registered entities.
     *
     * @return exporter bound to this factory's metadata cache
     */
    public SchemaExporter schemaExporter() {
        return new SchemaExporter(metadata);
    }

    /** Closes the pool, when this factory owns one. Sessions that are still open stop working. */
    @Override
    public void close() {
        if (ownedPool != null) {
            log.info("Closing the pool owned by this session factory");
            ownedPool.close();
        }
    }

    /** Fluent builder for {@link SessionFactory}. */
    public static final class Builder {

        private DataSource dataSource;
        private ConnectionPool pool;
        private PoolConfig poolConfig = PoolConfig.builder().build();
        private boolean poolRequested;
        private MetadataRegistry metadata = new MetadataRegistry();
        private IsolationLevel isolationLevel = IsolationLevel.READ_COMMITTED;
        private Dialect dialect = new PostgresDialect();
        private List<Class<?>> entities = List.of();

        private Builder() {
        }

        /**
         * Uses an existing data source. MicroORM then neither pools nor closes it.
         *
         * @param dataSource source of JDBC connections
         * @return this builder
         */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * Creates and owns a connection pool for the given data source.
         *
         * @param dataSource source of physical JDBC connections
         * @param poolConfig pool configuration
         * @return this builder
         */
        public Builder connectionPool(DataSource dataSource, PoolConfig poolConfig) {
            this.dataSource = dataSource;
            this.poolConfig = poolConfig;
            this.poolRequested = true;
            return this;
        }

        /**
         * Uses an already created pool and takes ownership of closing it.
         *
         * @param pool pool to use
         * @return this builder
         */
        public Builder pool(ConnectionPool pool) {
            this.pool = pool;
            this.dataSource = new PooledDataSource(pool);
            return this;
        }

        /**
         * @param config default isolation level for new transactions
         * @return this builder
         */
        public Builder isolationLevel(IsolationLevel config) {
            this.isolationLevel = config;
            return this;
        }

        /**
         * @param dialect SQL dialect
         * @return this builder
         */
        public Builder dialect(Dialect dialect) {
            this.dialect = dialect;
            return this;
        }

        /**
         * @param registry metadata cache to use, so that metadata can be prepared in advance
         * @return this builder
         */
        public Builder metadata(MetadataRegistry registry) {
            this.metadata = registry;
            return this;
        }

        /**
         * @param types entity classes to register eagerly
         * @return this builder
         */
        public Builder entities(Class<?>... types) {
            this.entities = List.of(types);
            return this;
        }

        /**
         * @return the configured factory
         * @throws IllegalStateException when neither a data source nor a pool was provided
         */
        public SessionFactory build() {
            if (dataSource == null) {
                throw new IllegalStateException("Either dataSource(...) or pool(...) is required");
            }
            // An explicit dataSource is used as is; a pool is created only when connectionPool(...)
            // asked for one, so that a foreign pool can stay in charge of connection reuse.
            if (pool == null && poolRequested) {
                pool = new ConnectionPool(dataSource, poolConfig);
            }
            return new SessionFactory(this);
        }
    }
}
