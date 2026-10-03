package io.microorm.support;

import io.microorm.annotation.Column;
import io.microorm.annotation.Entity;
import io.microorm.annotation.GeneratedValue;
import io.microorm.annotation.Id;
import io.microorm.annotation.ManyToOne;
import io.microorm.annotation.OneToMany;
import io.microorm.annotation.Table;
import io.microorm.example.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entity fixtures for the unit tests.
 *
 * <p>{@link io.microorm.example.User} and {@link io.microorm.example.Order} are deliberately absent:
 * the unit tests use the very same entities as the integration tests and the README, so a mapping
 * mistake cannot hide in a copy that only the fast tests see. What is left here are the shapes the
 * happy path cannot produce - unmappable types, two identifiers, a final class, an association to a
 * non-entity - plus a few column-type edge cases.
 */
public final class TestEntities {

    private TestEntities() {
    }

    /** Table name must come from {@code @Table}. */
    @Entity
    @Table(name = "audit_events")
    public static class AuditEvent {

        @Id
        private UUID id;

        private String message;

        public AuditEvent() {
        }

        public AuditEvent(UUID id, String message) {
            this.id = id;
            this.message = message;
        }

        public UUID getId() {
            return id;
        }

        public String getMessage() {
            return message;
        }
    }

    /** No {@code @Id} anywhere. */
    @Entity
    public static class NoId {

        private String name;

        public NoId() {
        }

        public String getName() {
            return name;
        }
    }

    /** Type that cannot be stored in a single column and has no association annotation. */
    @Entity
    public static class UnsupportedType {

        @Id
        private Long id;

        private List<String> tags;

        public UnsupportedType() {
        }

        public List<String> getTags() {
            return tags;
        }
    }

    /** Declaration order is used for INSERT column order. */
    @Entity(table = "ordered")
    public static class Ordered {

        @Id
        private Integer id;

        private String second;

        private String first;

        public Ordered() {
        }

        public Integer getId() {
            return id;
        }

        public String getSecond() {
            return second;
        }

        public String getFirst() {
            return first;
        }
    }

    /** Declares a LAZY association while being final, so it cannot be proxied. */
    @Entity
    public static final class FinalWithLazyAssociation {

        @Id
        private Long id;

        @ManyToOne
        private User user;

        public FinalWithLazyAssociation() {
        }

        public Long getId() {
            return id;
        }

        public User getUser() {
            return user;
        }
    }

    /** Uses the deliberately unimplemented one-to-many association. */
    @Entity
    public static class WithChildren {

        @Id
        private Long id;

        @OneToMany
        private List<User> users = new ArrayList<>();

        public WithChildren() {
        }

        public Long getId() {
            return id;
        }

        public List<User> getUsers() {
            return users;
        }
    }

    /** Without a no-argument constructor, which every entity needs. */
    @Entity
    public static class NoDefaultConstructor {

        @Id
        private Long id;

        public NoDefaultConstructor(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }
    }

    /** Identifier of an unsupported type. */
    @Entity
    public static class BadIdType {

        @Id
        private String id;

        public BadIdType() {
        }

        public String getId() {
            return id;
        }
    }

    /** Two identifiers. */
    @Entity
    public static class TwoIds {

        @Id
        private Long id;

        @Id
        private Long otherId;

        public TwoIds() {
        }

        public Long getId() {
            return id;
        }
    }

    /** {@code @GeneratedValue} outside of the identifier. */
    @Entity
    public static class GeneratedNonId {

        @Id
        private Long id;

        @GeneratedValue
        private Long counter;

        public GeneratedNonId() {
        }

        public Long getId() {
            return id;
        }

        public Long getCounter() {
            return counter;
        }
    }

    /** Two words in the class name, so the default table name must be snake_case. */
    @Entity
    public static class OrderLineItem {

        @Id
        private Long id;

        public OrderLineItem() {
        }

        public Long getId() {
            return id;
        }
    }

    /** Identifier without a getter, so it cannot be used to create a lazy proxy. */
    @Entity(table = "no_name")
    public static class NoName {

        @Id
        private Long id;

        private String label;

        public NoName() {
        }

        public Long id() {
            return id;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Association target that is not an entity, so no foreign key can be generated for it. */
    public static class NotAnEntityTarget {

        private Long id;

        public Long getId() {
            return id;
        }
    }

    @Entity(table = "orders_of_non_entity")
    public static class OrderOfNonEntity {

        @Id
        @GeneratedValue
        private Long id;

        @ManyToOne
        private NotAnEntityTarget user;

        public OrderOfNonEntity() {
        }

        public Long getId() {
            return id;
        }

        public NotAnEntityTarget getUser() {
            return user;
        }
    }

    /** Has an array column, which dirty checking has to compare by content. */
    @Entity(table = "blobs")
    public static class Blob {

        @Id
        private Long id;

        private byte[] content;

        public Blob() {
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public byte[] getContent() {
            return content;
        }

        public void setContent(byte[] content) {
            this.content = content;
        }
    }

    /** Numeric fields whose Java types are deliberately wider than the column types of the schema. */
    @Entity(table = "numbers")
    public static class Numbers {

        @Id
        private Long id;

        private Long longValue;

        private Integer intValue;

        private Double doubleValue;

        private Short shortValue;

        public Numbers() {
        }

        public Long getLongValue() {
            return longValue;
        }

        public Integer getIntValue() {
            return intValue;
        }

        public Double getDoubleValue() {
            return doubleValue;
        }

        public Short getShortValue() {
            return shortValue;
        }
    }

    /** Fixture for the value types the row mapper has to normalise. */
    @Entity(table = "types")
    public static class Types {

        @Id
        private Long id;

        private String textValue;

        private java.util.UUID uuidValue;

        private java.time.LocalDateTime timestampValue;

        public Types() {
        }

        public String getTextValue() {
            return textValue;
        }

        public java.util.UUID getUuidValue() {
            return uuidValue;
        }

        public java.time.LocalDateTime getTimestampValue() {
            return timestampValue;
        }
    }

    /** camelCase field names without {@code @Column}, so the naming convention applies. */
    @Entity(table = "camel_case")
    public static class CamelCaseColumns {

        @Id
        private Long id;

        private String firstName;

        private String lastName;

        public CamelCaseColumns() {
        }

        public Long getId() {
            return id;
        }

        public String getFirstName() {
            return firstName;
        }

        public String getLastName() {
            return lastName;
        }
    }

    /** Not an entity at all. */
    public static class NotAnEntity {

        private Long id;

        public Long getId() {
            return id;
        }
    }

    /** {@code @Table} must win over {@code @Entity(table)}. */
    @Entity(table = "ignored_name")
    @io.microorm.annotation.Table(name = "wins")
    public static class BothTableAnnotations {

        @Id
        private Long id;

        public BothTableAnnotations() {
        }

        public Long getId() {
            return id;
        }
    }
}