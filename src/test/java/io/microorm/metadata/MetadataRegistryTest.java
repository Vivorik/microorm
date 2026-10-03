package io.microorm.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.microorm.exception.MappingException;
import io.microorm.support.TestEntities;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetadataRegistryTest {

    private final MetadataParser parser = new MetadataParser();
    private final MetadataRegistry registry = new MetadataRegistry(parser);

    @Test
    @DisplayName("parses an entity only once no matter how often it is requested")
    void parsesEachEntityOnce() {
        EntityMetadata first = registry.metadataFor(TestEntities.User.class);
        EntityMetadata second = registry.metadataFor(TestEntities.User.class);

        assertThat(first).isSameAs(second);
        assertThat(parser.parsedEntityCount()).isEqualTo(1);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.isRegistered(TestEntities.User.class)).isTrue();
    }

    @Test
    @DisplayName("caches each entity separately")
    void cachesPerEntity() {
        registry.metadataFor(TestEntities.User.class);
        EntityMetadata order = registry.metadataFor(TestEntities.Order.class);

        assertThat(registry.size()).isEqualTo(2);
        assertThat(order.tableName()).isEqualTo("order");
        assertThat(parser.parsedEntityCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("concurrent lookups still parse an entity only once")
    void isSafeForConcurrentUse() throws InterruptedException, ExecutionException, TimeoutException {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // NOTE: submit first, release the latch afterwards - invokeAll would block while the
            // tasks still wait for the latch and deadlock the test.
            List<Future<EntityMetadata>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return registry.metadataFor(TestEntities.User.class);
                    }))
                    .toList();
            start.countDown();

            EntityMetadata expected = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<EntityMetadata> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(expected);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(parser.parsedEntityCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("eager registration reports metadata in registration order")
    void registersEagerly() {
        List<EntityMetadata> metadata = registry.register(TestEntities.Order.class, TestEntities.User.class);

        assertThat(metadata).map(EntityMetadata::type)
                .containsExactly(TestEntities.Order.class, TestEntities.User.class);
        assertThat(registry.knownMetadata()).hasSize(2);
        assertThat(registry.asMap()).containsOnlyKeys(TestEntities.Order.class, TestEntities.User.class);
    }

    @Test
    @DisplayName("evicting a class forces the next lookup to parse again")
    void evicts() {
        registry.metadataFor(TestEntities.User.class);
        registry.evict(TestEntities.User.class);

        assertThat(registry.isRegistered(TestEntities.User.class)).isFalse();
        registry.metadataFor(TestEntities.User.class);
        assertThat(parser.parsedEntityCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("propagates mapping errors of non-entities")
    void propagatesMappingErrors() {
        assertThatThrownBy(() -> registry.metadataFor(TestEntities.NotAnEntity.class))
                .isInstanceOf(MappingException.class);
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("describe() is used in log messages")
    void describesEntity() {
        assertThat(registry.metadataFor(TestEntities.User.class).describe()).isEqualTo("User(users)");
    }
}