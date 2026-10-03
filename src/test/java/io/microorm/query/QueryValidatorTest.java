package io.microorm.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.MappingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.microorm.example.User;

class QueryValidatorTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "'=', EQUAL",
            "'<>', NOT_EQUAL",
            "'>', GREATER",
            "'>=', GREATER_OR_EQUAL",
            "'<', LESS",
            "'<=', LESS_OR_EQUAL",
            "'LIKE', LIKE",
            "'not like', NOT_LIKE",
            "'IS NULL', IS_NULL",
            "'is not null', IS_NOT_NULL",
            "'IN', IN",
            "'NOT IN', NOT_IN"
    })
    @DisplayName("operators are accepted both as SQL text and as enum name")
    void parsesOperators(String text, ComparisonOperator expected) {
        assertThat(QueryValidator.operator(text)).isEqualTo(expected);
        assertThat(QueryValidator.operator(expected.name())).isEqualTo(expected);
        assertThat(QueryValidator.operator("  " + text + "  ")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {"==", "=<", "; DROP TABLE users", "LIKE 'x'", "UNION", ""})
    @DisplayName("an unknown or injectable operator is rejected")
    void rejectsUnknownOperator(String text) {
        assertThatThrownBy(() -> QueryValidator.operator(text))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Unsupported operator");
    }

    @Test
    @DisplayName("a null operator is rejected rather than causing a NullPointerException")
    void rejectsNullOperator() {
        assertThatThrownBy(() -> QueryValidator.operator(null))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Unsupported operator 'null'");
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({"asc, ASC", "ASC, ASC", "desc, DESC", " Desc , DESC"})
    @DisplayName("sort directions are parsed case insensitively")
    void parsesDirections(String text, SortDirection expected) {
        assertThat(QueryValidator.direction(text)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an unknown sort direction is rejected")
    void rejectsUnknownDirection() {
        assertThatThrownBy(() -> QueryValidator.direction("sideways"))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("Unsupported sort direction 'sideways'");
    }

    @Test
    @DisplayName("operators know whether they are unary or multi valued")
    void classifiesOperators() {
        assertThat(ComparisonOperator.IS_NULL.isUnary()).isTrue();
        assertThat(ComparisonOperator.IS_NOT_NULL.isUnary()).isTrue();
        assertThat(ComparisonOperator.EQUAL.isUnary()).isFalse();
        assertThat(ComparisonOperator.IN.isMultiValued()).isTrue();
        assertThat(ComparisonOperator.NOT_IN.isMultiValued()).isTrue();
        assertThat(ComparisonOperator.LIKE.isMultiValued()).isFalse();
        assertThat(ComparisonOperator.LIKE.sql()).isEqualTo("LIKE");
        assertThat(SortDirection.ASC.sql()).isEqualTo("ASC");
        assertThat(LogicalOperator.AND.sql()).isEqualTo("AND");
    }

    @Test
    @DisplayName("field() returns the mapped field for a known column")
    void resolvesField() {
        var user = new io.microorm.metadata.MetadataRegistry()
                .metadataFor(io.microorm.example.User.class);

        assertThat(QueryValidator.field(user, "email").name()).isEqualTo("email");
        assertThat(QueryValidator.field(user, "email").column()).isEqualTo("email");
    }
}