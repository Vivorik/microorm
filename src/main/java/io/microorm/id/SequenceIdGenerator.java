package io.microorm.id;

import io.microorm.metadata.EntityMetadata;
import io.microorm.sql.SqlGenerator;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Reads the next value of the sequence {@code <table>_id_seq} with {@code nextval('...')}.
 *
 * <p>The value is fetched inside the current transaction and bound as an ordinary column, which keeps
 * the identifier visible in the INSERT statement and in the logs.
 */
public final class SequenceIdGenerator implements IdGenerator {

    private final SqlGenerator sqlGenerator;

    public SequenceIdGenerator(SqlGenerator sqlGenerator) {
        this.sqlGenerator = sqlGenerator;
    }

    /**
     * @param entity metadata of the entity
     * @return name of the sequence for the entity's table
     */
    public String sequenceName(EntityMetadata entity) {
        return entity.tableName() + "_id_seq";
    }

    @Override
    public Optional<Object> generate(Connection connection, EntityMetadata entity) throws SQLException {
        String sql = sqlGenerator.nextSequenceValue(sequenceName(entity));
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("Sequence " + sequenceName(entity) + " returned no value");
            }
            Object value = result.getObject(1);
            if (value instanceof Number number) {
                return Optional.of(number.longValue());
            }
            throw new SQLException("Sequence " + sequenceName(entity) + " returned " + value
                    + ", expected a number");
        }
    }
}
