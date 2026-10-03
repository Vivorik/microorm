package io.microorm.benchmark;

import io.microorm.example.User;
import io.microorm.pool.ConnectionPool;
import io.microorm.pool.PoolConfig;
import io.microorm.session.Session;
import io.microorm.session.SessionFactory;
import io.microorm.support.DockerAvailability;
import io.microorm.support.PostgresFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares {@code find} by identifier in MicroORM with the same query written in plain JDBC.
 *
 * <p>Both sides run against the same PostgreSQL, over the same pooled connection, with the same SQL
 * projection. The difference the benchmark measures is therefore the ORM overhead itself: metadata
 * lookup, persistence context bookkeeping, reflection-based row mapping and the snapshot for dirty
 * checking.
 *
 * <p>Run it with:
 * <pre>{@code
 * mvn -q test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=org.openjdk.jmh.Main \
 *     -Dexec.args="io.microorm.benchmark.FindByIdBenchmark -bm avgt -wi 3 -i 5 -f 1"
 * }</pre>
 *
 * <p>The results are recorded in the README. Re-running them requires a Docker daemon, because the
 * fixture starts PostgreSQL in a container.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FindByIdBenchmark {

    private static final String SELECT_BY_ID = "SELECT id, email, name, age, active, bio, created_at, version "
            + "FROM users WHERE id = ?";

    private SessionFactory factory;
    private ConnectionPool pool;
    private DataSource dataSource;
    private Long userId;

    /**
     * Starts the container, creates the pool and inserts exactly one row to read.
     *
     * @throws SQLException when the fixture cannot prepare the database
     */
    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        DockerAvailability.assumeDocker();
        PostgresFixture.truncate(PostgresFixture.sessionFactory());

        pool = new ConnectionPool(PostgresFixture.rawDataSource(),
                PoolConfig.builder().minSize(2).maxSize(2).build());
        dataSource = new io.microorm.pool.PooledDataSource(pool);
        factory = SessionFactory.builder().pool(pool).build();

        try (Session session = factory.openSession()) {
            session.beginTransaction();
            User user = new User("ann@example.com", "Ann", 30, true);
            session.persist(user);
            session.commit();
            userId = user.getId();
        }
    }

    /** Closes the pool so that the forked JVM exits. */
    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    /**
     * MicroORM: opens a session, resolves metadata, checks the persistence context and maps the row
     * through reflection.
     *
     * @param blackhole sink
     * @return the loaded name
     */
    @Benchmark
    public String microOrmFindById(Blackhole blackhole) {
        try (Session session = factory.openSession()) {
            User user = session.findOrThrow(User.class, userId);
            blackhole.consume(user.getName());
            return user.getName();
        }
    }

    /**
     * MicroORM with a hot session: the session and its connection are reused, which isolates the cost of
     * the identity map and of row mapping.
     *
     * @param blackhole sink
     * @return the loaded name
     */
    @Benchmark
    public String microOrmFindByIdInWarmSession(Blackhole blackhole) {
        try (Session session = factory.openSession()) {
            User first = session.findOrThrow(User.class, userId);
            // The second call is answered from the first level cache and performs no SQL at all.
            User second = session.findOrThrow(User.class, userId);
            blackhole.consume(second.getName());
            return first.getName();
        }
    }

    /**
     * Plain JDBC: the same SQL, the same pooling, no ORM.
     *
     * @param blackhole sink
     * @return the loaded name
     * @throws SQLException when the query fails
     */
    @Benchmark
    public String plainJdbcFindById(Blackhole blackhole) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                String name = rows.getString("name");
                blackhole.consume(name);
                return name;
            }
        }
    }
}