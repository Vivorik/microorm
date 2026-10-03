package io.microorm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a one-to-many collection association.
 *
 * <p><strong>Not implemented.</strong> See the README section "What is not supported and why":
 * a persistent collection needs dirty tracking of collection elements, which is the part of an ORM
 * that hides the most surprising behaviour. Encountering this annotation throws
 * {@link UnsupportedOperationException} at metadata parsing time, so the limitation is discovered
 * at startup rather than at runtime.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface OneToMany {

    /** @return name of the foreign key column in the owning table */
    String mappedBy() default "";

    /** @return fetch strategy */
    FetchType fetch() default FetchType.LAZY;
}
