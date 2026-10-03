package io.microorm.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.MetadataRegistry;
import io.microorm.support.FakeJdbc;
import io.microorm.support.TestEntities;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Row mapping rules that a database can exercise and a fake result set cannot.
 *
 * <p>These cases come from running the integration tests: a {@code Long} field projected onto an
 * {@code INTEGER} column, for instance, makes the PostgreSQL driver refuse {@code getObject(i, Long)}.
 */
class EntityRowMapperTest {

    private final MetadataRegistry registry = new MetadataRegistry();
    private final FakeJdbc database = new FakeJdbc();

    private EntityMetadata entity() {
        return registry.metadataFor(TestEntities.Numbers.class);
    }

    private ResultSet row(String sql, Object... values) {
        database.onQuery(sql, values);
        return database.resultSetFor(sql);
    }

    private Object mapAll(EntityMetadata metadata, ResultSet rows) throws Exception {
        rows.next();
        return EntityRowMapper.map(metadata, rows, new NoAssociations());
    }

    private static final class NoAssociations implements EntityRowMapper.AssociationResolver {

        @Override
        public void resolve(io.microorm.metadata.FieldMetadata field, Object foreignKey, Object owner) {
            throw new UnsupportedOperationException("The fixture has no association fields");
        }

        @Override
        public Class<?> columnTypeOf(io.microorm.metadata.FieldMetadata field) {
            throw new UnsupportedOperationException("The fixture has no association fields");
        }
    }

    @Test
    @DisplayName("a wider Java number type than the column type is converted instead of rejected")
    void widensNumericTypes() throws Exception {
        String sql = "SELECT id, long_value, int_value, double_value, short_value FROM numbers";
        ResultSet rows = row(sql, 1, 7L, 8, 1.5d, (short) 3);

        TestEntities.Numbers numbers =
                (TestEntities.Numbers) mapAll(entity(), rows);

        assertThat(numbers.getLongValue()).isEqualTo(7L);
        assertThat(numbers.getIntValue()).isEqualTo(8);
        assertThat(numbers.getDoubleValue()).isEqualTo(1.5d);
        assertThat(numbers.getShortValue()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("a narrower Java number type than the column type is narrowed")
    void narrowsNumericTypes() throws Exception {
        String sql = "SELECT id, long_value, int_value, double_value, short_value FROM numbers";
        ResultSet rows = row(sql, 1, 7, 8, 1.5f, 3);

        TestEntities.Numbers numbers =
                (TestEntities.Numbers) mapAll(entity(), rows);

        assertThat(numbers.getLongValue()).isEqualTo(7L);
        assertThat(numbers.getIntValue()).isEqualTo(8);
        assertThat(numbers.getDoubleValue()).isEqualTo(1.5d);
        assertThat(numbers.getShortValue()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("values that already match the field type are taken as they are")
    void keepsMatchingValues() throws Exception {
        String sql = "SELECT id, text_value, uuid_value, timestamp_value FROM types";
        ResultSet rows = row(sql, 1L, "text", UUID.nameUUIDFromBytes(new byte[]{1}),
                LocalDateTime.of(2026, 1, 2, 3, 4));

        TestEntities.Types numbers = readTypes(rows);

        assertThat(numbers.getTextValue()).isEqualTo("text");
        assertThat(numbers.getUuidValue()).isEqualTo(UUID.nameUUIDFromBytes(new byte[]{1}));
        assertThat(numbers.getTimestampValue()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4));
    }

    private TestEntities.Types readTypes(ResultSet rows) throws Exception {
        rows.next();
        return (TestEntities.Types) EntityRowMapper.map(
                registry.metadataFor(TestEntities.Types.class), rows, new NoAssociations());
    }

    @Test
    @DisplayName("an SQL NULL becomes null, not a primitive default")
    void mapsNull() throws Exception {
        String sql = "SELECT id, long_value, int_value, double_value, short_value FROM numbers";
        ResultSet rows = row(sql, 1L, null, null, null, null);

        TestEntities.Numbers numbers =
                (TestEntities.Numbers) mapAll(entity(), rows);

        assertThat(numbers.getLongValue()).isNull();
        assertThat(numbers.getIntValue()).isNull();
        assertThat(numbers.getDoubleValue()).isNull();
        assertThat(numbers.getShortValue()).isNull();
    }

    @Test
    @DisplayName("reading a column index out of range fails instead of returning a wrong value")
    void failsOnWrongIndex() throws Exception {
        String sql = "SELECT id, long_value, int_value, double_value, short_value FROM numbers";
        ResultSet rows = row(sql, 1L, 7L, 8, 1.5d, (short) 3);
        rows.next();

        assertThatThrownBy(() -> EntityRowMapper.read(rows, 99, Long.class))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("Column index 99 is out of range");
    }

    @Test
    @DisplayName("reading before the result set is positioned on a row fails")
    void failsBeforeNext() throws Exception {
        String sql = "SELECT id, long_value, int_value, double_value, short_value FROM numbers";
        ResultSet rows = row(sql, 1L, 7L, 8, 1.5d, (short) 3);

        assertThatThrownBy(() -> EntityRowMapper.read(rows, 1, Long.class))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("not positioned on a row");
    }

    @Test
    @DisplayName("a non-numeric value that does not fit the field type is passed to the driver")
    void delegatesNonNumericMismatchToDriver() throws Exception {
        String sql = "SELECT id, long_value, int_value, double_value, short_value FROM numbers";
        ResultSet rows = row(sql, 1L, "not a number", 8, 1.5d, (short) 3);
        rows.next();

        // A String cannot be read as a Long; the mapper has no business inventing a value, so the
        // failure comes from the conversion the driver performs.
        assertThatThrownBy(() -> EntityRowMapper.read(rows, 2, Long.class))
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    @DisplayName("a row without an identifier is still mapped; rejecting it is the session's job")
    void mapsRowWithoutIdentifier() throws Exception {
        String sql = "SELECT id, long_value, int_value, double_value, short_value FROM numbers";
        ResultSet rows = row(sql, null, 7L, 8, 1.5d, (short) 3);

        assertThat(mapAll(entity(), rows)).isInstanceOf(TestEntities.Numbers.class);
    }
}