package com.vy.sales.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Step 0 of the DB+Redis session-store migration (replacing the Caffeine claims cache): a single
 * kill switch so the new write path (and later, the new validation path) can be deployed dark and
 * flipped on/off without a redeploy.
 *
 * <p>{@code AUTH_SESSION_STORE_ENABLED=true} enables:
 *
 * <ul>
 *   <li>writing a durable {@code auth_sessions} row on login/refresh
 *   <li>writing a Redis {@code session:{jti}} cache entry on login/refresh
 *   <li>the shadow revocation check in JwtAuthWebFilter (log-only — does not deny requests yet)
 * </ul>
 *
 * Default is {@code false}: none of the above runs, and behavior is identical to before this
 * migration started.
 */
@Component
@ConfigurationProperties(prefix = "security.session-store")
@Getter
@Setter
public class SessionStoreProperties {
  private boolean enabled = false;
}
