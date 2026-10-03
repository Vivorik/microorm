-- Schema for the integration tests, applied by the Testcontainers PostgreSQL container.
-- The generator produces the same DDL; SchemaExporterIT proves that by applying it to a clean
-- database and running the same scenarios against it.

-- users: BIGSERIAL, so @GeneratedValue(AUTO) resolves to an identity column
-- (there is deliberately no users_id_seq sequence).
CREATE TABLE users (
    id         BIGSERIAL    PRIMARY KEY,
    email      VARCHAR(255) NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL,
    age        INTEGER,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    bio        TEXT,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    version    INTEGER      NOT NULL DEFAULT 0
);

-- orders: a sequence exists, so @GeneratedValue(SEQUENCE) and AUTO both resolve to it.
CREATE SEQUENCE orders_id_seq;
CREATE TABLE orders (
    id          BIGINT        PRIMARY KEY DEFAULT nextval('orders_id_seq'),
    user_id     BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    description VARCHAR(255)  NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    version     INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX idx_orders_user_id ON orders (user_id);

-- tickets: only exists to prove that AUTO resolves to a sequence when the schema has one.
CREATE SEQUENCE tickets_id_seq;
CREATE TABLE tickets (
    id          BIGINT       PRIMARY KEY DEFAULT nextval('tickets_id_seq'),
    description VARCHAR(255) NOT NULL,
    version     INTEGER      NOT NULL DEFAULT 0
);
