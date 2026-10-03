package io.microorm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an integer version field enabling optimistic locking.
 *
 * <p>The field is incremented on every UPDATE and added to the {@code WHERE} clause,
 * so a concurrent modification of the same row raises
 * {@link io.microorm.exception.OptimisticLockException}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Version {
}
