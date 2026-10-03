package io.microorm.annotation;

/** Fetch strategy for associations. */
public enum FetchType {

    /** The value is materialised together with the owning entity. */
    EAGER,

    /** The value is represented by a proxy that loads on first access. */
    LAZY
}
