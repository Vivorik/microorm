package io.microorm.sql;

import io.microorm.metadata.SqlTypeResolver;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes {@link Bind} parameters into a {@link PreparedStatement}.
 *
 * <p>Binding is centralised for one reason: a single place decides how each supported type is sent,
 * which is what an SQL injection bug needs. There is no path from a value to SQL text.
 */
public final class ParameterBinder {

    private static final Logger log = LoggerFactory.getLogger(ParameterBinder.class);

    private ParameterBinder() {
        throw new AssertionError("No instances of ParameterBinder");
    }

    /**
     * Binds every parameter in placeholder order.
     *
     * @param statement  prepared statement
     * @param parameters bind parameters
     * @throws SQLException when the driver rejects a value or a type
     */
    public static void bind(PreparedStatement statement, List<Bind> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            bind(statement, i + 1, parameters.get(i));
        }
        if (log.isTraceEnabled()) {
            log.trace("Bound {} parameters", parameters.size());
        }
    }

    /**
     * Binds a single parameter.
     *
     * <p>{@code null} is sent with {@code setNull} and an explicit JDBC type: without the type the
     * driver has to guess, and PostgreSQL rejects an untyped {@code null} for several column types.
     *
     * @param statement prepared statement
     * @param position  one-based placeholder index
     * @param parameter value and its type
     * @throws SQLException when the driver rejects the value
     */
    public static void bind(PreparedStatement statement, int position, Bind parameter) throws SQLException {
        if (parameter.isNull()) {
            statement.setNull(position, SqlTypeResolver.resolveJdbcType(parameter.javaType()));
            return;
        }
        // NOTE: PreparedStatement.setObject(int, Object, Class) only accepts types bounded by
        // SQLXML, so the few types that have a dedicated setter are dispatched explicitly. Everything
        // else (java.time, UUID, byte[]) goes through setObject(Object), which the PostgreSQL driver
        // maps from the Java type.
        Object value = parameter.value();
        switch (parameter.javaType().getName()) {
            case "java.lang.Long" -> statement.setLong(position, (Long) value);
            case "java.lang.Integer" -> statement.setInt(position, (Integer) value);
            case "java.lang.Short" -> statement.setShort(position, (Short) value);
            case "java.lang.Boolean" -> statement.setBoolean(position, (Boolean) value);
            case "java.lang.String" -> statement.setString(position, (String) value);
            case "java.math.BigDecimal" -> statement.setBigDecimal(position, (BigDecimal) value);
            case "java.lang.Double" -> statement.setDouble(position, (Double) value);
            case "java.lang.Float" -> statement.setFloat(position, (Float) value);
            default -> statement.setObject(position, value);
        }
    }

    /**
     * Renders a statement for debug logging.
     *
     * @param statement statement to render
     * @return SQL text followed by the parameters in brackets
     */
    public static String describe(SqlStatement statement) {
        return statement.sql() + " " + statement.parameters();
    }
}