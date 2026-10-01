-- Shows
CREATE TABLE shows (
    id            UUID PRIMARY KEY,
    name          VARCHAR(255) NOT NULL UNIQUE,
    price_paise   BIGINT       NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT          NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats   INT          NOT NULL CHECK (total_seats > 0),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seats — one row per physical seat
CREATE TABLE seats (
    id             UUID PRIMARY KEY,
    show_id        UUID         NOT NULL REFERENCES shows(id),
    seat_number    VARCHAR(20)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'available'
                       CHECK (status IN ('available','held','confirmed')),
    user_id        VARCHAR(255),
    reservation_id UUID,
    held_until     TIMESTAMPTZ,
    UNIQUE (show_id, seat_number)
);

CREATE INDEX idx_seats_show_status ON seats (show_id, status);
CREATE INDEX idx_seats_show_user   ON seats (show_id, user_id) WHERE user_id IS NOT NULL;

-- Reservations
CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    show_id         UUID         NOT NULL REFERENCES shows(id),
    user_id         VARCHAR(255) NOT NULL,
    seats_csv       TEXT         NOT NULL,          -- comma-separated seat numbers
    seat_count      INT          NOT NULL,
    amount_paise    BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'confirmed'
                        CHECK (status IN ('confirmed','cancelled','expired')),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_reservations_show_user ON reservations (show_id, user_id, status);
