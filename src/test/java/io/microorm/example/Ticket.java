package io.microorm.example;

import io.microorm.annotation.Entity;
import io.microorm.annotation.GeneratedValue;
import io.microorm.annotation.Id;

/**
 * Entity mapped to a table that has a sequence, used to verify that
 * {@link io.microorm.annotation.GenerationType#AUTO} resolves to that sequence instead of an identity
 * column. The schema of {@code users} (identity, no sequence) and of {@code tickets} (sequence) is what
 * makes the two branches of {@code AUTO} observable.
 */
@Entity(table = "tickets")
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
