package io.microorm.metadata;

import io.microorm.exception.MappingException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates SQL identifiers before they are concatenated into a statement.
 *
 * <p>MicroORM never accepts a raw column or table name from user input: every name reaches SQL text
 * only after passing through this class. Column <em>values</em> are always sent as bind parameters,
 * but identifiers cannot be parameterised in JDBC, so they are whitelisted instead.
 */
public final class IdentifierValidator {

    /** PostgreSQL truncates identifiers at 63 bytes; staying below the limit also keeps messages readable. */
    private static final int MAX_LENGTH = 63;

    private static final Pattern VALID = Pattern.compile("[a-z_][a-z0-9_]*");

    private IdentifierValidator() {
        throw new AssertionError("No instances of IdentifierValidator");
    }

    /**
     * Checks that the identifier is a plain lower-case SQL name.
     *
     * @param identifier candidate table or column name
     * @return the same identifier, for convenient chaining
     * @throws MappingException when the identifier is empty, too long or contains anything else than
     *                          {@code [a-z_][a-z0-9_]*}
     */
    public static String validate(String identifier) {
        Optional<String> problem = findProblem(identifier);
        if (problem.isPresent()) {
            throw new MappingException("Invalid SQL identifier '" + identifier + "': " + problem.get());
        }
        return identifier;
    }

    /**
     * Describes why an identifier is not acceptable.
     *
     * @param identifier candidate identifier, may be {@code null}
     * @return empty when the identifier is valid
     */
    public static Optional<String> findProblem(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.of("identifier must not be blank");
        }
        if (identifier.length() > MAX_LENGTH) {
            return Optional.of("identifier is longer than " + MAX_LENGTH + " characters");
        }
        if (!VALID.matcher(identifier).matches()) {
            return Optional.of("only [a-z_][a-z0-9_]* is allowed");
        }
        return Optional.empty();
    }

    /**
     * Converts a Java identifier into {@code snake_case}, used to derive default table names.
     *
     * <p>{@code OrderItem} becomes {@code order_item}, {@code HTTPRequest} becomes {@code http_request}.
     *
     * @param camelCase Java-style name
     * @return lower-case name with underscores, never blank for a non-blank input
     */
    public static String toSnakeCase(String camelCase) {
        StringBuilder result = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            boolean boundary = i > 0 && Character.isUpperCase(c)
                    && !Character.isUpperCase(camelCase.charAt(i - 1));
            if (boundary) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }
}