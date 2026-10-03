package io.microorm.proxy;

import io.microorm.metadata.EntityMetadata;
import io.microorm.metadata.FieldMetadata;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Creates lazy proxies for {@code @ManyToOne} associations and for {@code getReference()}.
 *
 * <p>ByteBuddy generates a subclass of the entity class at runtime. Every method except the
 * identifier accessor is intercepted: the first call loads the real instance, and every later call -
 * reads and writes alike - is delegated to it. The proxy therefore never holds a copy of the state,
 * which is what makes a write through a proxy end up on the managed instance and be flushed like any
 * other change.
 *
 * <p>The alternative, {@code java.lang.reflect.Proxy}, only works for interfaces, and entities are
 * classes. {@code @SuperCall} is what lets the interceptor invoke the original implementation: it is
 * ByteBuddy's equivalent of {@code super.method(...)}.
 *
 * <p>Intercepted methods must not be {@code final}, which is why the metadata parser rejects an
 * entity class that is final and declares a lazy association.
 */
public final class LazyProxyFactory {

    private static final String STATE_FIELD = "$$microOrmState";
    private static final String INITIALIZED_FIELD = "$$microOrmInitialized";
    private static final String IDENTIFIER_FIELD = "$$microOrmId";
    private static final String TARGET_FIELD = "$$microOrmTarget";

    private final ByteBuddy byteBuddy = new ByteBuddy();

    /**
     * Generated classes are cached per entity type.
     *
     * <p>Generating a subclass is expensive, and every proxy of the same entity has to share one class
     * anyway - otherwise {@code instanceof} checks would still pass but {@code getClass()} comparisons
     * would surprise, and the metaspace would fill up with one class per proxy.
     */
    private final Map<Class<?>, GeneratedProxyType> generatedTypes = new ConcurrentHashMap<>();

    /** Loads the real instance behind a proxy. */
    @FunctionalInterface
    public interface EntityLoader {

        /**
         * @param id identifier the proxy stands for
         * @return the real entity
         * @throws io.microorm.exception.LazyInitializationException when the owning session is unusable
         */
        Object load(Object id);
    }

    /**
     * Creates a proxy for an entity.
     *
     * @param entity metadata of the entity, used to locate the identifier field
     * @param id     identifier value the proxy stands for
     * @param loader callback that loads the real instance on first use
     * @param <T>    entity type
     * @return a proxy instance of {@code entity.type()}
     * @throws IllegalStateException when the entity has no getter for its identifier
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxy(EntityMetadata entity, Object id, EntityLoader loader) {
        FieldMetadata identifier = entity.identifier();
        Class<?> proxyType = generatedTypes
                .computeIfAbsent(entity.type(), ignored -> generate(entity, identifier))
                .proxyType();
        return (T) instantiate(proxyType, identifier, entity, id, loader);
    }

    private GeneratedProxyType generate(EntityMetadata entity, FieldMetadata identifier) {
        String identifierAccessor = accessorOf(entity, identifier);
        Class<?> type = entity.type();

        Class<?> proxyType = byteBuddy
                .subclass(type)
                .name(type.getName() + "$MicroOrmProxy")
                .implement(EntityProxy.class)
                .defineField(STATE_FIELD, ProxyState.class, Visibility.PRIVATE)
                .defineField(INITIALIZED_FIELD, boolean.class, Visibility.PRIVATE)
                .defineField(TARGET_FIELD, Object.class, Visibility.PRIVATE)
                // NOTE: the identifier is answered from a field declared on the generated type.
                // FieldAccessor resolves fields on the instrumented class only, and the entity's own
                // field is usually private in a superclass, hence the dedicated field.
                .defineField(IDENTIFIER_FIELD, identifier.javaType(), Visibility.PRIVATE)
                .method(ElementMatchers.named(identifierAccessor))
                .intercept(FieldAccessor.ofField(IDENTIFIER_FIELD))
                .method(ElementMatchers.named("isMicroOrmInitialized"))
                .intercept(FieldAccessor.ofField(INITIALIZED_FIELD))
                .method(ElementMatchers.not(ElementMatchers.named(identifierAccessor))
                        .and(ElementMatchers.not(ElementMatchers.named("isMicroOrmInitialized"))))
                .intercept(MethodDelegation.to(LazyInitInterceptor.class))
                .make()
                .load(classLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        return new GeneratedProxyType(proxyType, identifierAccessor);
    }

    /** The generated subclass plus the accessor that answers the identifier without loading. */
    private record GeneratedProxyType(Class<?> proxyType, String identifierAccessor) {
    }

    private Object instantiate(
            Class<?> proxyType,
            FieldMetadata identifier,
            EntityMetadata entity,
            Object id,
            EntityLoader loader) {
        try {
            Object proxy = proxyType.getDeclaredConstructor().newInstance();
            setField(proxy, STATE_FIELD, new ProxyState(entity, id, loader));
            // Seeding both identifier fields is what makes getId() answerable without a SELECT and
            // keeps the proxy consistent with the entity it will turn into.
            setField(proxy, IDENTIFIER_FIELD, id);
            identifier.field().set(proxy, id);
            return proxy;
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot instantiate a lazy proxy for " + entity.describe()
                    + "; a usable no-argument constructor is required", e);
        }
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue with the superclass: the field may be declared there.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : LazyProxyFactory.class.getClassLoader();
    }

    /**
     * Derives the getter name of a field: {@code firstName -> getFirstName}.
     *
     * @param entity metadata of the entity
     * @param field  field whose accessor is needed
     * @return name of the no-argument getter returning the field type
     * @throws IllegalStateException when the entity has no such getter
     */
    private String accessorOf(EntityMetadata entity, FieldMetadata field) {
        String suffix = Character.toUpperCase(field.name().charAt(0)) + field.name().substring(1);
        for (Method method : entity.type().getMethods()) {
            if (method.getParameterCount() == 0
                    && method.getName().equals("get" + suffix)
                    && method.getReturnType() == field.javaType()) {
                return method.getName();
            }
        }
        throw new IllegalStateException("Entity " + entity.describe() + " has no public getter for '"
                + field.name() + "'; add one, e.g. public " + field.javaType().getSimpleName()
                + " get" + suffix + "()");
    }

    /**
     * Per-proxy state: which entity, which identifier, how to load it, and the loaded instance.
     *
     * <p>A class rather than a record because the loaded target and the initialized flag have to be
     * mutable, and the interceptor updates them in place rather than replacing the state object the
     * proxy holds.
     */
    public static final class ProxyState {

        private final EntityMetadata entity;
        private final Object id;
        private final EntityLoader loader;
        private volatile Object target;

        ProxyState(EntityMetadata entity, Object id, EntityLoader loader) {
            this.entity = entity;
            this.id = id;
            this.loader = loader;
        }

        /** @return metadata of the proxied entity */
        public EntityMetadata entity() {
            return entity;
        }

        /** @return identifier the proxy stands for */
        public Object id() {
            return id;
        }

        /** @return callback that loads the real instance */
        public EntityLoader loader() {
            return loader;
        }

        /** @return the loaded instance, {@code null} while the proxy is still uninitialized */
        public Object target() {
            return target;
        }

        /** @return {@code true} once the real instance has been loaded */
        public boolean initialized() {
            return target != null;
        }

        private void initialized(Object loaded) {
            this.target = loaded;
        }
    }

    /** Interceptor installed on every method of a proxy except the identifier accessor. */
    public static final class LazyInitInterceptor {

        private LazyInitInterceptor() {
        }

        /**
         * Loads the target on the first call and delegates to it afterwards.
         *
         * @param proxy  the proxy instance
         * @param method invoked method, used to special-case the {@code Object} methods
         * @param args   call arguments
         * @param state  state of this proxy
         * @return the result of the delegated call
         * @throws Exception whatever the loader or the target throws
         */
        @net.bytebuddy.implementation.bind.annotation.RuntimeType
        public static Object intercept(
                @This Object proxy,
                @Origin Method method,
                @AllArguments Object[] args,
                @FieldValue(STATE_FIELD) ProxyState state) throws Exception {

            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> state.initialized() ? state.target().toString() : describe(state);
                default -> method.invoke(target(proxy, state), args);
            };
        }

        /**
         * @return the loaded instance, loading it on first use
         */
        private static Object target(Object proxy, ProxyState state) {
            if (!state.initialized()) {
                // NOTE: the loaded instance stays managed by the session, and the proxy delegates to it
                // instead of copying its state. A copy would be a second source of truth: writes through
                // the proxy would never reach the unit of work.
                Object loaded = state.loader().load(state.id());
                state.initialized(loaded);
                markInitialized(proxy, loaded);
            }
            return state.target();
        }

        private static void markInitialized(Object proxy, Object loaded) {
            try {
                setField(proxy, INITIALIZED_FIELD, Boolean.TRUE);
                setField(proxy, TARGET_FIELD, loaded);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot mark a lazy proxy as initialized", e);
            }
        }

        private static String describe(ProxyState state) {
            return state.entity().describe() + " proxy for id " + state.id();
        }
    }
}