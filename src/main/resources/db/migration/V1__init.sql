-- HaulMate core schema. Shared by the Java API and the Celery worker.

CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    full_name         VARCHAR(160) NOT NULL,
    phone             VARCHAR(20)  NOT NULL UNIQUE,
    email             VARCHAR(160),
    password_hash     VARCHAR(100) NOT NULL,
    role              VARCHAR(20)  NOT NULL,            -- SHIPPER, DRIVER, FLEET_OWNER, ADMIN
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING_KYC', -- PENDING_KYC, ACTIVE, SUSPENDED
    kyc_status        VARCHAR(20)  NOT NULL DEFAULT 'NONE',        -- NONE, PENDING, VERIFIED, REJECTED
    national_id_number VARCHAR(40),
    licence_number    VARCHAR(40),
    licence_class     VARCHAR(10),
    company_name      VARCHAR(160),
    fleet_owner_id    BIGINT REFERENCES users(id),
    fcm_token         VARCHAR(400),
    rating_avg        NUMERIC(3,2) NOT NULL DEFAULT 0,
    rating_count      INT NOT NULL DEFAULT 0,
    wallet_balance    NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kyc_documents (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL REFERENCES users(id),
    doc_type          VARCHAR(30) NOT NULL,   -- NATIONAL_ID, PASSPORT, DRIVERS_LICENCE, SELFIE, LIVENESS_FRAME, VEHICLE_REG, INSURANCE, ZINARA_DISC
    capture_mode      VARCHAR(10) NOT NULL,   -- LIVE, UPLOAD
    file_path         VARCHAR(400) NOT NULL,
    liveness_challenge VARCHAR(30),           -- BLINK, TURN_LEFT, TURN_RIGHT, SMILE (frames only)
    ocr_text          TEXT,
    extracted_json    TEXT,
    face_match_score  DOUBLE PRECISION,
    liveness_passed   BOOLEAN,
    auto_checks       TEXT,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, PROCESSING, AUTO_PASSED, AUTO_FLAGGED, APPROVED, REJECTED
    reviewer_id       BIGINT REFERENCES users(id),
    review_note       VARCHAR(500),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ
);
CREATE INDEX idx_kyc_user ON kyc_documents(user_id);
CREATE INDEX idx_kyc_status ON kyc_documents(status);

CREATE TABLE trucks (
    id              BIGSERIAL PRIMARY KEY,
    owner_id        BIGINT NOT NULL REFERENCES users(id),
    driver_id       BIGINT REFERENCES users(id),
    reg_number      VARCHAR(20) NOT NULL UNIQUE,
    make            VARCHAR(60),
    model           VARCHAR(60),
    truck_class     VARCHAR(20) NOT NULL,  -- PICKUP_1T, LIGHT_3T, MEDIUM_8T, HEAVY_15T, SUPER_30T
    body_type       VARCHAR(20) NOT NULL,  -- OPEN, CLOSED_BOX, FLATBED, TIPPER, TAUTLINER, REFRIGERATED, TANKER, LOWBED
    capacity_tonnes NUMERIC(6,2) NOT NULL,
    verified        BOOLEAN NOT NULL DEFAULT FALSE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE loads (
    id                  BIGSERIAL PRIMARY KEY,
    ref                 VARCHAR(20) NOT NULL UNIQUE,
    shipper_id          BIGINT NOT NULL REFERENCES users(id),
    pickup_address      VARCHAR(300) NOT NULL,
    pickup_lat          DOUBLE PRECISION NOT NULL,
    pickup_lng          DOUBLE PRECISION NOT NULL,
    dropoff_address     VARCHAR(300) NOT NULL,
    dropoff_lat         DOUBLE PRECISION NOT NULL,
    dropoff_lng         DOUBLE PRECISION NOT NULL,
    distance_km         DOUBLE PRECISION NOT NULL,
    cargo_type          VARCHAR(40) NOT NULL,
    cargo_description   VARCHAR(500),
    weight_tonnes       NUMERIC(6,2) NOT NULL,
    truck_class         VARCHAR(20) NOT NULL,
    body_type           VARCHAR(20),
    hazardous           BOOLEAN NOT NULL DEFAULT FALSE,
    fragile             BOOLEAN NOT NULL DEFAULT FALSE,
    refrigerated        BOOLEAN NOT NULL DEFAULT FALSE,
    urgent              BOOLEAN NOT NULL DEFAULT FALSE,
    pickup_from         TIMESTAMPTZ,
    pickup_to           TIMESTAMPTZ,
    recommended_price   NUMERIC(12,2) NOT NULL,
    offered_price       NUMERIC(12,2) NOT NULL,
    agreed_price        NUMERIC(12,2),
    currency            VARCHAR(3) NOT NULL DEFAULT 'USD',
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    search_radius_km    INT NOT NULL DEFAULT 0,
    drivers_alerted     INT NOT NULL DEFAULT 0,
    driver_id           BIGINT REFERENCES users(id),
    truck_id            BIGINT REFERENCES trucks(id),
    eta_pickup_minutes  INT,
    eta_dropoff_minutes INT,
    payment_method      VARCHAR(20) NOT NULL DEFAULT 'CASH',
    paid                BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_pin        VARCHAR(6),
    pickup_photo        VARCHAR(400),
    delivery_photo      VARCHAR(400),
    cancel_reason       VARCHAR(300),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at         TIMESTAMPTZ,
    picked_up_at        TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ
);
CREATE INDEX idx_loads_status ON loads(status);
CREATE INDEX idx_loads_pickup ON loads(pickup_lat, pickup_lng);

CREATE TABLE offers (
    id                  BIGSERIAL PRIMARY KEY,
    load_id             BIGINT NOT NULL REFERENCES loads(id),
    driver_id           BIGINT NOT NULL REFERENCES users(id),
    truck_id            BIGINT NOT NULL REFERENCES trucks(id),
    amount              NUMERIC(12,2) NOT NULL,
    counter             BOOLEAN NOT NULL DEFAULT FALSE,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    distance_to_pickup_km DOUBLE PRECISION,
    eta_minutes         INT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_offers_load ON offers(load_id);

CREATE TABLE load_alerts (
    load_id     BIGINT NOT NULL REFERENCES loads(id),
    driver_id   BIGINT NOT NULL REFERENCES users(id),
    radius_km   INT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (load_id, driver_id)
);

CREATE TABLE location_pings (
    id          BIGSERIAL PRIMARY KEY,
    driver_id   BIGINT NOT NULL REFERENCES users(id),
    load_id     BIGINT REFERENCES loads(id),
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    speed_kmh   DOUBLE PRECISION,
    heading     DOUBLE PRECISION,
    recorded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_pings_load ON location_pings(load_id, recorded_at);

CREATE TABLE chat_messages (
    id          BIGSERIAL PRIMARY KEY,
    load_id     BIGINT NOT NULL REFERENCES loads(id),
    sender_id   BIGINT REFERENCES users(id),
    msg_type    VARCHAR(12) NOT NULL DEFAULT 'TEXT',  -- TEXT, IMAGE, SYSTEM, CALL_LOG
    body        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ
);
CREATE INDEX idx_chat_load ON chat_messages(load_id, created_at);

CREATE TABLE payments (
    id              BIGSERIAL PRIMARY KEY,
    load_id         BIGINT NOT NULL REFERENCES loads(id),
    payer_id        BIGINT NOT NULL REFERENCES users(id),
    amount          NUMERIC(12,2) NOT NULL,
    commission      NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency        VARCHAR(3) NOT NULL DEFAULT 'USD',
    method          VARCHAR(20) NOT NULL,   -- PAYNOW_WEB, ECOCASH, ONEMONEY, INNBUCKS, CASH
    mode            VARCHAR(4)  NOT NULL,   -- TEST, LIVE
    reference       VARCHAR(40) NOT NULL UNIQUE,
    paynow_reference VARCHAR(40),
    poll_url        VARCHAR(400),
    browser_url     VARCHAR(400),
    instructions    VARCHAR(500),
    status          VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    raw_status      VARCHAR(40),
    released        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ratings (
    id          BIGSERIAL PRIMARY KEY,
    load_id     BIGINT NOT NULL REFERENCES loads(id),
    from_user   BIGINT NOT NULL REFERENCES users(id),
    to_user     BIGINT NOT NULL REFERENCES users(id),
    stars       INT NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment     VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (load_id, from_user)
);

CREATE TABLE settings (
    key         VARCHAR(80) PRIMARY KEY,
    value       TEXT,
    secret      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(160)
);

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id),
    title       VARCHAR(160) NOT NULL,
    body        VARCHAR(500) NOT NULL,
    data_json   TEXT,
    sent        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Defaults (USD). Editable from Admin > Settings.
INSERT INTO settings(key, value, secret) VALUES
 ('paynow.mode', 'TEST', false),
 ('paynow.test.integration_id', '', false),
 ('paynow.test.integration_key', '', true),
 ('paynow.test.auth_email', '', false),
 ('paynow.live.integration_id', '', false),
 ('paynow.live.integration_key', '', true),
 ('paynow.live.auth_email', '', false),
 ('commission.percent', '10', false),
 ('pricing.reference_diesel', '1.60', false),
 ('pricing.current_diesel', '1.60', false),
 ('pricing.road_factor', '1.25', false),
 ('pricing.empty_return_factor', '0.15', false),
 ('pricing.km_per_toll_gate', '90', false),
 ('pricing.PICKUP_1T',  '15,0.55,2', false),
 ('pricing.LIGHT_3T',   '25,0.85,3', false),
 ('pricing.MEDIUM_8T',  '40,1.30,5', false),
 ('pricing.HEAVY_15T',  '60,1.80,8', false),
 ('pricing.SUPER_30T',  '90,2.60,10', false),
 ('pricing.surcharge.hazardous', '0.25', false),
 ('pricing.surcharge.fragile', '0.10', false),
 ('pricing.surcharge.refrigerated', '0.30', false),
 ('pricing.surcharge.urgent', '0.15', false),
 ('matching.radius_steps_km', '10,25,50,150,400,1200', false),
 ('matching.step_seconds', '180', false),
 ('routing.osrm_url', '', false),
 ('routing.avg_speed_kmh', '55', false),
 ('kyc.auto_approve', 'false', false),
 ('kyc.face_match_threshold', '0.36', false),
 ('webrtc.stun_url', 'stun:stun.l.google.com:19302', false),
 ('webrtc.turn_url', '', false),
 ('webrtc.turn_username', '', false),
 ('webrtc.turn_password', '', true);
