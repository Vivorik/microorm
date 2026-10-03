package io.microorm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a many-to-one association, e.g. an {@code Order} referencing its {@code User}.
 *
 * <p>A {@code LAZY} association is stored as a proxy created by
 * {@link io.microorm.proxy.LazyProxyFactory}; touching any accessor except the identifier triggers
 * a SELECT. Cascading is not supported.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ManyToOne {

    /** @return name of the foreign key column, defaults to {@code <field>_<referenced id column>} */
    String joinColumn() default "";

    /** @return fetch strategy */
    FetchType fetch() default FetchType.LAZY;

    /** @return whether a {@code NULL} association is allowed */
    boolean optional() default true;
}
