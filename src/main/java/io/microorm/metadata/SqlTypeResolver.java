package io.microorm.metadata;

import io.microorm.exception.MappingException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Maps Java types to PostgreSQL column types.
 *
 * <p>MicroORM supports a deliberately small set of "basic" types, mirroring the JPA basic types
 * that actually show up in practice. Anything else must be stored through
 * {@link io.microorm.annotation.ManyToOne}, which keeps the SQL mapping table-driven and
 * predictable instead of guessing.
 */
public final class SqlTypeResolver {

    private static final Map<Class<?>, String> TYPES = Map.ofEntries(
            Map.entry(Long.class, "BIGINT"),
            Map.entry(Integer.class, "INTEGER"),
            Map.entry(Short.class, "SMALLINT"),
            Map.entry(Boolean.class, "BOOLEAN"),
            Map.entry(String.class, "VARCHAR(255)"),
            Map.entry(BigDecimal.class, "NUMERIC(19,2)"),
            Map.entry(Double.class, "DOUBLE PRECISION"),
            Map.entry(Float.class, "REAL"),
            Map.entry(LocalDateTime.class, "TIMESTAMP"),
            Map.entry(LocalDate.class, "DATE"),
            Map.entry(Instant.class, "TIMESTAMPTZ"),
            Map.entry(UUID.class, "UUID"),
            Map.entry(byte[].class, "BYTEA"),
            Map.entry(Object.class, "TEXT"));

    private SqlTypeResolver() {
        throw new AssertionError("No instances of SqlTypeResolver");
    }

    /**
     * @param javaType declared field type
     * @return {@code true} when values of this type can be written to and read from a single column
     */
    public static boolean isBasicType(Class<?> javaType) {
        return TYPES.containsKey(javaType);
    }

    /**
     * Resolves the default column definition for a basic type.
     *
     * @param javaType declared field type
     * @return PostgreSQL type as used in {@code CREATE TABLE}
     * @throws MappingException when the type is not a supported basic type
     */
    public static String resolveColumnType(Class<?> javaType) {
        String type = TYPES.get(javaType);
        if (type == null) {
            throw new MappingException("Unsupported field type " + javaType.getName()
                    + "; supported basic types: " + TYPES.keySet());
        }
        return type;
    }

    /**
     * Resolves a JDBC type constant used when binding a value with {@code setNull}.
     *
     * @param javaType declared field type
     * @return constant from {@link java.sql.Types}
     * @throws MappingException when the type is not a supported basic type
     */
    public static int resolveJdbcType(Class<?> javaType) {
        return switch (javaType.getName()) {
            case "java.lang.Long", "long" -> java.sql.Types.BIGINT;
            case "java.lang.Integer", "int" -> java.sql.Types.INTEGER;
            case "java.lang.Short", "short" -> java.sql.Types.SMALLINT;
            case "java.lang.Boolean", "boolean" -> java.sql.Types.BOOLEAN;
            case "java.lang.String" -> java.sql.Types.VARCHAR;
            case "java.math.BigDecimal" -> java.sql.Types.NUMERIC;
            case "java.lang.Double", "double" -> java.sql.Types.DOUBLE;
            case "java.lang.Float", "float" -> java.sql.Types.REAL;
            case "java.time.LocalDateTime" -> java.sql.Types.TIMESTAMP;
            case "java.time.LocalDate" -> java.sql.Types.DATE;
            case "java.time.Instant" -> java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
            case "java.util.UUID" -> java.sql.Types.OTHER;
            default -> {
                if (javaType == byte[].class) {
                    yield java.sql.Types.BINARY;
                }
                throw new MappingException("No JDBC type for " + javaType.getName());
            }
        };
    }
}