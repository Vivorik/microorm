package io.microorm.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.microorm.example.Order;
import io.microorm.example.User;
import io.microorm.metadata.EntityMetadata;
import io.microorm.schema.SchemaExporter;
import io.microorm.session.SessionFactory;
import io.microorm.support.DockerAvailability;
import io.microorm.support.PostgresFixture;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that the generated DDL is real DDL: it is applied to an empty PostgreSQL schema and the
 * resulting tables are inspected through JDBC.
 */
class SchemaExporterIT {

    private static final String SCHEMA = "generated_by_microorm";

    private static SessionFactory factory;

    @BeforeAll
    static void startDatabase() {
        DockerAvailability.assumeDocker();
        factory = SessionFactory.builder()
                .dataSource(PostgresFixture.rawDataSource())
                .entities(User.class, Order.class)
                .build();

        PostgresFixture.execute(factory, "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        PostgresFixture.execute(factory, "CREATE SCHEMA " + SCHEMA);
    }

    @Test
    @DisplayName("the generated script creates tables, a sequence and a foreign key")
    void generatedSchemaIsExecutable() throws SQLException {
        SchemaExporter exporter = factory.schemaExporter();
        List<EntityMetadata> entities = factory.knownEntities();
        String script = exporter.script(entities);

        assertThat(script)
                .contains("CREATE SEQUENCE IF NOT EXISTS orders_id_seq;")
                .contains("CREATE TABLE users (")
                .contains("CREATE TABLE orders (")
                .contains("FOREIGN KEY (user_id) REFERENCES users (id)");

        applyInOwnSchema(script);

        try (Connection connection = connectionInGeneratedSchema();
             Statement statement = connection.createStatement()) {
            assertThat(tableExists(statement, "users")).isTrue();
            assertThat(tableExists(statement, "orders")).isTrue();
            assertThat(tableExists(statement, "orders_id_seq")).isTrue();

            try (ResultSet columns = connection.getMetaData().getColumns(null, SCHEMA, "users", null)) {
                List<String> names = new java.util.ArrayList<>();
                while (columns.next()) {
                    names.add(columns.getString("COLUMN_NAME"));
                }
                assertThat(names).containsExactlyInAnyOrder("id", "email", "name", "age", "active",
                        "bio", "created_at", "version");
            }

            try (ResultSet constraints = connection.getMetaData()
                    .getImportedKeys(null, SCHEMA, "orders")) {
                assertThat(constraints.next()).isTrue();
                assertThat(constraints.getString("FKTABLE_NAME")).isEqualToIgnoringCase("orders");
                assertThat(constraints.getString("PKTABLE_NAME")).isEqualToIgnoringCase("users");
            }
        }
    }

    @Test
    @DisplayName("the generated identity column produces identifiers on insert")
    void generatedIdentityWorks() throws SQLException {
        try (Connection connection = connectionInGeneratedSchema();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users (email, name, active) VALUES ('a@b.c', 'Ann', true)");

            try (ResultSet rows = statement.executeQuery("SELECT id, version FROM users")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("id")).isPositive();
                assertThat(rows.getInt("version")).isZero();
            }
        }
    }

    @Test
    @DisplayName("the generated sequence produces identifiers on insert")
    void generatedSequenceWorks() throws SQLException {
        try (Connection connection = connectionInGeneratedSchema();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO orders (user_id, description, amount) SELECT id, 'book', 10.00 FROM users");

            try (ResultSet rows = statement.executeQuery("SELECT id FROM orders")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isPositive();
            }
        }
    }

    private void applyInOwnSchema(String script) throws SQLException {
        try (Connection connection = connectionInGeneratedSchema();
             Statement statement = connection.createStatement()) {
            for (String command : script.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("--"))
                    .toList()) {
                statement.execute(command);
            }
        }
    }

    private Connection connectionInGeneratedSchema() throws SQLException {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setUrl(PostgresFixture.jdbcUrl() + "&currentSchema=" + SCHEMA);
        dataSource.setUser(PostgresFixture.container().getUsername());
        dataSource.setPassword(PostgresFixture.container().getPassword());
        return dataSource.getConnection();
    }

    private boolean tableExists(Statement statement, String name) throws SQLException {
        try (ResultSet tables = statement.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + name + "'")) {
            return tables.next();
        }
    }
}