package io.microorm.example;

import io.microorm.annotation.Entity;
import io.microorm.annotation.FetchType;
import io.microorm.annotation.GeneratedValue;
import io.microorm.annotation.Id;
import io.microorm.annotation.ManyToOne;
import io.microorm.annotation.Version;
import java.math.BigDecimal;

/**
 * Example entity with a lazy many-to-one association, mirroring the {@code orders} table.
 */
@Entity(table = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = io.microorm.annotation.GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    private String description;

    private BigDecimal amount;

    @Version
    private Long version;

    public Order() {
    }

    public Order(String description, BigDecimal amount) {
        this.description = description;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Long getVersion() {
        return version;
    }
}
