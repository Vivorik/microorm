package io.microorm.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.LazyInitializationException;
import io.microorm.metadata.MetadataRegistry;
import io.microorm.support.TestEntities;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LazyProxyFactoryTest {

    private final MetadataRegistry registry = new MetadataRegistry();
    private final LazyProxyFactory factory = new LazyProxyFactory();

    private TestEntities.User stored(long id, String name) {
        TestEntities.User user = new TestEntities.User(name + "@example.com", name, 30, true);
        user.setId(id);
        return user;
    }

    @Test
    @DisplayName("creating a proxy does not touch the loader")
    void doesNotLoadOnCreation() {
        AtomicInteger loads = new AtomicInteger();

        TestEntities.User proxy = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 1L, id -> {
                    loads.incrementAndGet();
                    return stored((Long) id, "Ann");
                });

        assertThat(proxy).isNotNull();
        assertThat(proxy).isInstanceOf(TestEntities.User.class);
        assertThat(proxy).isInstanceOf(EntityProxy.class);
        assertThat(((EntityProxy) proxy).isMicroOrmInitialized()).isFalse();
        assertThat(loads).hasValue(0);
    }

    @Test
    @DisplayName("the identifier is answered from the field without loading")
    void identifierDoesNotTriggerLoading() {
        AtomicInteger loads = new AtomicInteger();
        TestEntities.User proxy = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 42L, id -> {
                    loads.incrementAndGet();
                    return stored((Long) id, "Ann");
                });

        assertThat(proxy.getId()).isEqualTo(42L);
        assertThat(proxy.getId()).isEqualTo(42L);
        assertThat(loads).hasValue(0);
    }

    @Test
    @DisplayName("the first access to any other property loads the entity exactly once")
    void loadsOnFirstPropertyAccess() {
        AtomicInteger loads = new AtomicInteger();
        TestEntities.User proxy = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 1L, id -> {
                    loads.incrementAndGet();
                    return stored((Long) id, "Ann");
                });

        assertThat(proxy.getName()).isEqualTo("Ann");
        assertThat(loads).hasValue(1);
        assertThat(((EntityProxy) proxy).isMicroOrmInitialized()).isTrue();

        assertThat(proxy.getEmail()).isEqualTo("Ann@example.com");
        assertThat(proxy.getAge()).isEqualTo(30);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("a loaded proxy behaves like a full entity")
    void behavesLikeTheEntity() {
        TestEntities.User stored = stored(5L, "Bob");
        stored.setBio("hi");
        TestEntities.User proxy = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 5L, id -> stored);

        assertThat(proxy.getEmail()).isEqualTo("Bob@example.com");
        assertThat(proxy.getBio()).isEqualTo("hi");
        assertThat(proxy.getId()).isEqualTo(5L);
        assertThat(proxy.getActive()).isTrue();
    }

    @Test
    @DisplayName("writing to a proxy is allowed and reaches the loaded state")
    void allowsWriting() {
        TestEntities.User proxy = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 1L, id -> stored((Long) id, "Ann"));

        proxy.getName();
        proxy.setName("Anna");

        assertThat(proxy.getName()).isEqualTo("Anna");
    }

    @Test
    @DisplayName("toString describes the proxy without loading it")
    void describesWithoutLoading() {
        AtomicInteger loads = new AtomicInteger();
        TestEntities.User proxy = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 7L, id -> {
                    loads.incrementAndGet();
                    return stored((Long) id, "Ann");
                });

        assertThat(proxy.toString()).isEqualTo("User(users) proxy for id 7");
        assertThat(loads).hasValue(0);

        proxy.getName();

        assertThat(proxy.toString()).contains(TestEntities.User.class.getName());
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("equals and hashCode use identity, like a reference to a not yet loaded row")
    void usesIdentitySemantics() {
        TestEntities.User proxy = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 1L, id -> stored((Long) id, "Ann"));
        TestEntities.User other = stored(1L, "Ann");

        assertThat(proxy).isNotEqualTo(other);
        assertThat(proxy).isEqualTo(proxy);
        assertThat(proxy.hashCode()).isEqualTo(System.identityHashCode(proxy));
    }

    @Test
    @DisplayName("a proxy that is dereferenced after the session closed fails loudly")
    void failsWhenSessionIsClosed() {
        boolean[] sessionOpen = {true};
        TestEntities.User proxy = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 1L, id -> {
                    if (!sessionOpen[0]) {
                        throw new LazyInitializationException(
                                "Cannot load User(users) with id 1: the session is already closed");
                    }
                    return stored((Long) id, "Ann");
                });
        sessionOpen[0] = false;

        assertThatThrownBy(proxy::getName)
                .isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("session is already closed");
        assertThat(proxy.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("two proxies for the same row share the entity type and stay independent instances")
    void createsIndependentProxies() {
        TestEntities.User first = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 1L, id -> stored((Long) id, "Ann"));
        TestEntities.User second = factory.createProxy(
                registry.metadataFor(TestEntities.User.class), 1L, id -> stored((Long) id, "Ann"));

        assertThat(first).isNotSameAs(second);
        assertThat(first.getClass()).isEqualTo(second.getClass());
        assertThat(first.getClass().getName()).endsWith("$MicroOrmProxy");
        assertThat(first.getName()).isEqualTo("Ann");
        assertThat(second.getName()).isEqualTo("Ann");
    }

    @Test
    @DisplayName("an entity without a getter for its identifier cannot be proxied")
    void reportsMissingIdentifierGetter() {
        assertThatThrownBy(() -> factory.createProxy(registry.metadataFor(TestEntities.NoName.class), 1L,
                        id -> stored(1L, "Ann")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no public getter for 'id'");
    }
}