CREATE TABLE users (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Stored normalized, so the unique constraint also covers differences in letter case.
    email         varchar(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash varchar(255) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now()
);
