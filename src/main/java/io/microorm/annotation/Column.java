package io.microorm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Overrides the column name and nullability of a mapped field. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {

    /** @return physical column name, defaults to the field name */
    String name() default "";

    /** @return whether the column accepts {@code NULL} */
    boolean nullable() default true;

    /** @return column definition used by DDL generation, e.g. {@code varchar(255)} */
    String columnDefinition() default "";

    /** @return whether the column participates in INSERT statements */
    boolean insertable() default true;

    /** @return whether the column participates in UPDATE statements */
    boolean updatable() default true;
}
