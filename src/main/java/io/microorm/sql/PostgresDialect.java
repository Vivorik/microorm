package io.microorm.sql;

import io.microorm.metadata.IdentifierValidator;

/** PostgreSQL dialect: {@code LIMIT}/{@code OFFSET} and {@code nextval('sequence')}. */
public final class PostgresDialect implements Dialect {

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public String sequenceNextValue(String sequenceName) {
        // The identifier is validated first: it is concatenated into SQL text, unlike bind values.
        return "SELECT nextval('" + IdentifierValidator.validate(sequenceName) + "')";
    }

    @Override
    public String pagination(Integer limit, Integer offset) {
        StringBuilder clause = new StringBuilder();
        if (limit != null) {
            clause.append("LIMIT ").append(limit);
        }
        if (offset != null) {
            clause.append(clause.isEmpty() ? "OFFSET " : " OFFSET ").append(offset);
        }
        return clause.toString();
    }
}
