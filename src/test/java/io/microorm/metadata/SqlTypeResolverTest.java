package io.microorm.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.MappingException;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlTypeResolverTest {

    static Stream<Arguments> basicTypes() {
        return Stream.of(
                Arguments.of(Long.class, "BIGINT"),
                Arguments.of(Integer.class, "INTEGER"),
                Arguments.of(Short.class, "SMALLINT"),
                Arguments.of(Boolean.class, "BOOLEAN"),
                Arguments.of(String.class, "VARCHAR(255)"),
                Arguments.of(BigDecimal.class, "NUMERIC(19,2)"),
                Arguments.of(Double.class, "DOUBLE PRECISION"),
                Arguments.of(Float.class, "REAL"),
                Arguments.of(LocalDateTime.class, "TIMESTAMP"),
                Arguments.of(LocalDate.class, "DATE"),
                Arguments.of(Instant.class, "TIMESTAMPTZ"),
                Arguments.of(UUID.class, "UUID"),
                Arguments.of(byte[].class, "BYTEA"));
    }

    static Stream<Arguments> jdbcTypes() {
        return Stream.of(
                Arguments.of(Long.class, Types.BIGINT),
                Arguments.of(Integer.class, Types.INTEGER),
                Arguments.of(Short.class, Types.SMALLINT),
                Arguments.of(Boolean.class, Types.BOOLEAN),
                Arguments.of(String.class, Types.VARCHAR),
                Arguments.of(BigDecimal.class, Types.NUMERIC),
                Arguments.of(Double.class, Types.DOUBLE),
                Arguments.of(Float.class, Types.REAL),
                Arguments.of(LocalDateTime.class, Types.TIMESTAMP),
                Arguments.of(LocalDate.class, Types.DATE),
                Arguments.of(Instant.class, Types.TIMESTAMP_WITH_TIMEZONE),
                Arguments.of(UUID.class, Types.OTHER),
                Arguments.of(byte[].class, Types.BINARY));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("basicTypes")
    @DisplayName("maps the supported Java types to PostgreSQL column types")
    void resolvesColumnTypes(Class<?> type, String expected) {
        assertThat(SqlTypeResolver.isBasicType(type)).isTrue();
        assertThat(SqlTypeResolver.resolveColumnType(type)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> JDBC {1}")
    @MethodSource("jdbcTypes")
    @DisplayName("maps the supported Java types to JDBC constants, used for setNull")
    void resolvesJdbcTypes(Class<?> type, int expected) {
        assertThat(SqlTypeResolver.resolveJdbcType(type)).isEqualTo(expected);
    }

    @Test
    @DisplayName("unknown types are neither basic nor resolvable")
    void rejectsUnknownTypes() {
        assertThat(SqlTypeResolver.isBasicType(Thread.class)).isFalse();
        assertThatThrownBy(() -> SqlTypeResolver.resolveColumnType(Thread.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Unsupported field type java.lang.Thread")
                .hasMessageContaining("supported basic types");
        assertThatThrownBy(() -> SqlTypeResolver.resolveJdbcType(Thread.class))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("No JDBC type for java.lang.Thread");
    }
}