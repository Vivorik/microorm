package io.microorm.example;

import io.microorm.annotation.Column;
import io.microorm.annotation.Entity;
import io.microorm.annotation.GeneratedValue;
import io.microorm.annotation.Id;
import io.microorm.annotation.Transient;
import io.microorm.annotation.Version;
import java.time.LocalDateTime;

/**
 * Example entity, mirroring the {@code users} table of the integration test schema.
 *
 * <p>The class is deliberately a plain POJO: no-arg constructor, non-final getters, private fields with
 * annotations. Those are the only requirements MicroORM places on an entity.
 */
@Entity(table = "users")
public class User {

    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    private String name;

    private Integer age;

    private Boolean active;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Version
    private int version;

    /** Not mapped: the {@code transient} keyword keeps it out of the SQL. */
    private transient String password;

    public User() {
    }

    public User(String email, String name, Integer age, Boolean active) {
        this.email = email;
        this.name = name;
        this.age = age;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public int getVersion() {
        return version;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
