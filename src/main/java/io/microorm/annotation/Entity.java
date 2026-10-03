package io.microorm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a persistent entity: MicroORM will map its annotated fields to a table.
 *
 * <p>An entity class must be a non-final class with a no-argument constructor, because
 * {@link io.microorm.proxy.LazyProxyFactory} creates runtime subclasses of it.
 *
 * <p>Precedence rule: when both {@link Table} and this annotation declare a table name,
 * {@code @Table} wins.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Entity {

    /**
     * Logical name of the table. Overridden by {@link Table#name()} when the latter is present.
     *
     * @return table name, defaults to the simple class name in snake_case
     */
    String table() default "";
}
