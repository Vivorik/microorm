package io.microorm.proxy;

/**
 * Implemented by every lazy proxy MicroORM generates.
 *
 * <p>Lets the session tell "this is a proxy" apart from "this is a real entity" without scanning
 * class names, and lets tests assert that no SELECT happened yet.
 */
public interface EntityProxy {

    /** @return {@code true} once the proxy has loaded the real instance */
    boolean isMicroOrmInitialized();
}