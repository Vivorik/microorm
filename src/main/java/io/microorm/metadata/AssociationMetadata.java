package io.microorm.metadata;

import io.microorm.annotation.FetchType;

/**
 * Description of an association field: which table and column hold the foreign key, how eagerly it
 * is loaded and what type the referenced object has.
 *
 * @param name       field name in the owning entity
 * @param type       kind of association
 * @param joinColumn physical column that stores the foreign key, e.g. {@code user_id}
 * @param targetType Java type of the referenced entity, needed to create lazy proxies
 * @param fetch      eager or lazy
 * @param optional   whether the foreign key may be {@code NULL}
 */
public record AssociationMetadata(
        String name,
        AssociationType type,
        String joinColumn,
        Class<?> targetType,
        FetchType fetch,
        boolean optional) {

    /** @return {@code true} when the association is represented by a lazy proxy */
    public boolean lazy() {
        return fetch == FetchType.LAZY;
    }
}
