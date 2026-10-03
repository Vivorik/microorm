package io.microorm.metadata;

import io.microorm.annotation.Column;
import io.microorm.annotation.Entity;
import io.microorm.annotation.GeneratedValue;
import io.microorm.annotation.Id;
import io.microorm.annotation.ManyToOne;
import io.microorm.annotation.OneToMany;
import io.microorm.annotation.Table;
import io.microorm.annotation.Transient;
import io.microorm.annotation.Version;
import io.microorm.exception.MappingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns an annotated class into {@link EntityMetadata} using reflection.
 *
 * <p>Parsing is intentionally strict: anything MicroORM cannot map unambiguously raises
 * {@link MappingException} while the metadata is built, i.e. on the first use of the entity, rather
 * than in the middle of a transaction.
 *
 * <p>Results are cached by {@link MetadataRegistry}, so an instance of this class is used through the
 * registry rather than called directly by application code.
 */
public class MetadataParser {

    /** Identifier types MicroORM accepts, deliberately narrow and immutable. */
    private static final Set<Class<?>> SUPPORTED_ID_TYPES = Set.of(Long.class, Integer.class, UUID.class);

    private static final Set<Class<?>> SUPPORTED_VERSION_TYPES = Set.of(Long.class, Integer.class);

    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = Map.of(
            long.class, Long.class,
            int.class, Integer.class,
            short.class, Short.class,
            boolean.class, Boolean.class,
            double.class, Double.class,
            float.class, Float.class,
            byte.class, Byte.class);

    private final AtomicInteger parsedEntities = new AtomicInteger();

    /**
     * Builds metadata for one entity class.
     *
     * @param type class annotated with {@link Entity}
     * @return derived metadata
     * @throws MappingException           when the class cannot be mapped
     * @throws UnsupportedOperationException when a deliberately unsupported mapping is declared
     */
    public EntityMetadata parse(Class<?> type) {
        parsedEntities.incrementAndGet();
        validateType(type);
        Constructor<?> constructor = noArgConstructor(type);
        String tableName = resolveTableName(type);

        List<FieldMetadata> fields = new ArrayList<>();
        FieldMetadata identifier = null;
        FieldMetadata version = null;
        for (Field field : type.getDeclaredFields()) {
            if (isIgnored(field)) {
                continue;
            }
            FieldMetadata metadata = parseField(field);
            if (metadata.identifier()) {
                identifier = requireSingle(type, identifier, metadata, "@Id");
            }
            if (metadata.version()) {
                version = requireSingle(type, version, metadata, "@Version");
            }
            fields.add(metadata);
        }

        if (identifier == null) {
            throw new MappingException(type.getName() + " has no @Id field");
        }
        validateIdentifier(type, identifier);
        Optional.ofNullable(version).ifPresent(v -> validateVersion(type, v));
        validateProxyability(type, fields);
        return EntityMetadata.of(type, tableName, constructor, identifier, Optional.ofNullable(version), fields);
    }

    /** @return how many times {@link #parse(Class)} has actually done reflection work */
    public int parsedEntityCount() {
        return parsedEntities.get();
    }

    private void validateType(Class<?> type) {
        if (type.isInterface() || type.isEnum() || type.isAnnotation() || type.isRecord()) {
            throw new MappingException(type.getName()
                    + " cannot be an entity: interfaces, enums, annotations and records are not supported");
        }
        if (type.getAnnotation(Entity.class) == null) {
            throw new MappingException(type.getName() + " is not annotated with @Entity");
        }
    }

    private Constructor<?> noArgConstructor(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            // NOTE: private constructors are usable as long as we may break encapsulation, which is
            // always the case for application classes on the classpath (unnamed module).
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new MappingException(type.getName()
                    + " must declare a no-argument constructor", e);
        }
    }

    private String resolveTableName(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        String name = table != null ? table.name() : type.getAnnotation(Entity.class).table();
        if (name.isBlank()) {
            name = IdentifierValidator.toSnakeCase(type.getSimpleName());
        }
        return IdentifierValidator.validate(name);
    }

    private boolean isIgnored(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers)
                || field.isSynthetic()
                || Modifier.isTransient(modifiers)
                || field.getAnnotation(Transient.class) != null;
    }

    private FieldMetadata parseField(Field field) {
        if (field.getAnnotation(OneToMany.class) != null) {
            throw new UnsupportedOperationException("@OneToMany on " + field
                    + " is not implemented: see the README section 'What is not supported and why'");
        }
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        return manyToOne != null
                ? associationField(field, manyToOne)
                : basicField(field);
    }

    private FieldMetadata associationField(Field field, ManyToOne association) {
        if (field.getAnnotation(Column.class) != null) {
            throw new MappingException("@Column cannot be combined with @ManyToOne on " + field);
        }
        boolean identifier = field.getAnnotation(Id.class) != null;
        String joinColumn = association.joinColumn().isBlank()
                ? field.getName() + "_id"
                : association.joinColumn();
        AssociationMetadata metadata = new AssociationMetadata(
                field.getName(),
                AssociationType.MANY_TO_ONE,
                IdentifierValidator.validate(joinColumn),
                field.getType(),
                association.fetch(),
                association.optional());
        return new FieldMetadata(field.getName(), metadata.joinColumn(), field.getType(), field,
                identifier, false, Optional.empty(), Optional.of(metadata),
                association.optional(), !identifier, !identifier, "");
    }

    private FieldMetadata basicField(Field field) {
        boolean identifier = field.getAnnotation(Id.class) != null;
        boolean version = field.getAnnotation(Version.class) != null;
        Class<?> boxed = boxed(field.getType());
        if (!SqlTypeResolver.isBasicType(boxed)) {
            throw new MappingException("Cannot map field " + field + ": type " + field.getType().getName()
                    + " is not a basic type and carries no @ManyToOne annotation");
        }
        Column column = field.getAnnotation(Column.class);
        // NOTE: default naming convention is snake_case, so that every identifier reaching SQL
        // satisfies IdentifierValidator and matches how PostgreSQL projects are usually written.
        String columnName = column != null && !column.name().isBlank()
                ? column.name()
                : IdentifierValidator.toSnakeCase(field.getName());
        boolean primitive = field.getType().isPrimitive();
        boolean nullable = column == null ? !primitive : column.nullable();

        Optional<io.microorm.annotation.GenerationType> generation = Optional.ofNullable(
                field.getAnnotation(GeneratedValue.class)).map(GeneratedValue::strategy);
        if (generation.isPresent() && !identifier) {
            throw new MappingException("@GeneratedValue is only allowed on @Id fields, found on " + field);
        }
        return new FieldMetadata(field.getName(), IdentifierValidator.validate(columnName), boxed, field,
                identifier, version, generation, Optional.empty(), nullable,
                column == null || column.insertable(), column == null || column.updatable(),
                column == null ? "" : column.columnDefinition());
    }

    private void validateIdentifier(Class<?> type, FieldMetadata identifier) {
        if (!SUPPORTED_ID_TYPES.contains(identifier.javaType())) {
            throw new MappingException("@Id of " + type.getName() + " must be one of "
                    + SUPPORTED_ID_TYPES + " but was " + identifier.javaType().getName());
        }
    }

    private void validateVersion(Class<?> type, FieldMetadata version) {
        if (!SUPPORTED_VERSION_TYPES.contains(version.javaType())) {
            throw new MappingException("@Version of " + type.getName() + " must be an integral type but was "
                    + version.javaType().getName());
        }
    }

    private void validateProxyability(Class<?> type, List<FieldMetadata> fields) {
        boolean needsProxy = fields.stream()
                .flatMap(f -> f.association().stream())
                .anyMatch(AssociationMetadata::lazy);
        if (needsProxy && Modifier.isFinal(type.getModifiers())) {
            throw new MappingException(type.getName()
                    + " declares a LAZY association and must not be final: lazy loading needs a generated subclass");
        }
    }

    private FieldMetadata requireSingle(
            Class<?> type, FieldMetadata current, FieldMetadata candidate, String annotation) {
        if (current != null) {
            throw new MappingException(type.getName() + " declares " + annotation + " more than once");
        }
        return candidate;
    }

    private Class<?> boxed(Class<?> type) {
        return PRIMITIVE_WRAPPERS.getOrDefault(type, type);
    }
}