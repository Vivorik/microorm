package io.microorm.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.MappingException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifierValidatorTest {

    @ParameterizedTest(name = "\"{0}\" is valid")
    @ValueSource(strings = {"id", "user_id", "_private", "a", "table_1"})
    @DisplayName("accepts plain lower-case identifiers")
    void acceptsValidIdentifiers(String identifier) {
        assertThat(IdentifierValidator.validate(identifier)).isEqualTo(identifier);
        assertThat(IdentifierValidator.findProblem(identifier)).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {
            "",
            "  ",
            "User",
            "user id",
            "user; DROP TABLE users",
            "user-id",
            "user\"",
            "'quoted'",
            "1st_column",
            "имя"})
    @DisplayName("rejects anything that is not [a-z_][a-z0-9_]*")
    void rejectsInvalidIdentifiers(String identifier) {
        assertThat(IdentifierValidator.findProblem(identifier)).isPresent();
        assertThatThrownBy(() -> IdentifierValidator.validate(identifier))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Invalid SQL identifier");
    }

    @Test
    @DisplayName("rejects identifiers longer than the PostgreSQL limit")
    void rejectsOverlongIdentifier() {
        String overlong = "a".repeat(64);

        assertThat(IdentifierValidator.findProblem(overlong))
                .contains("identifier is longer than 63 characters");
    }

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
        Optional<String> problem = IdentifierValidator.findProblem(null);

        assertThat(problem).contains("identifier must not be blank");
    }

    @Test
    @DisplayName("converts camelCase to snake_case for default table names")
    void convertsToSnakeCase() {
        assertThat(IdentifierValidator.toSnakeCase("User")).isEqualTo("user");
        assertThat(IdentifierValidator.toSnakeCase("OrderItem")).isEqualTo("order_item");
        assertThat(IdentifierValidator.toSnakeCase("HTTPRequest")).isEqualTo("httprequest");
        assertThat(IdentifierValidator.toSnakeCase("AuditEventLog")).isEqualTo("audit_event_log");
        assertThat(IdentifierValidator.toSnakeCase("already_snake")).isEqualTo("already_snake");
    }
}