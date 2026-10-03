package io.microorm.session;

import java.sql.Connection;

/**
 * Supplies the JDBC connection of the current session.
 *
 * <p>Lets the statement executing classes borrow the session connection without depending on the
 * session itself.
 */
@FunctionalInterface
interface SqlConnectionProvider {

    /** @return the connection of the session */
    Connection connection();
}
