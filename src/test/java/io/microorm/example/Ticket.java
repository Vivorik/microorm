package io.microorm.example;

import io.microorm.annotation.Entity;
import io.microorm.annotation.GeneratedValue;
import io.microorm.annotation.Id;

/**
 * Second entity mapped to the {@code orders} table, used to verify that
 * {@link io.microorm.annotation.GenerationType#AUTO} resolves to a sequence when the schema has one.
 *
 * <p>Mapping two classes to one table is legal here and is the point: the two differ only in the
 * generation strategy, so the difference in behaviour is exactly what the test observes.
 */
@Entity(table = "orders")
public class Ticket {

    @Id
    @GeneratedValue
    private Long id;

    private String description;

    public Ticket() {
    }

    public Ticket(String description) {
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
