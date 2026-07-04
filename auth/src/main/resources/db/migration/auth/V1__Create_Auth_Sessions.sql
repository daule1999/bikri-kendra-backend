-- Session-of-record for issued tokens. Durable source of truth backing the Redis
-- session:{jti} cache used on the request-validation hot path (see JwtAuthWebFilter).
-- Not read on that hot path itself — only written at login/refresh/force-logout, and
-- read for audit / "list my sessions" / admin force-logout-by-user features.
CREATE TABLE auth_sessions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    jti             VARCHAR(40)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    username        VARCHAR(150) NOT NULL,
    issued_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    revoked_at      TIMESTAMP    NULL,

    UNIQUE KEY uq_auth_sessions_jti (jti),
    INDEX idx_auth_sessions_user_id (user_id),
    INDEX idx_auth_sessions_expires_at (expires_at)
);
