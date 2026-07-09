package com.vy.sales.platform.logging;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for {@link ApiLoggingWebFilter}. All overridable via env:
 * API_LOGGING_ENABLED, API_LOGGING_LOG_BODIES, API_LOGGING_MAX_BODY_BYTES.
 */
@ConfigurationProperties(prefix = "observability.api-logging")
public record ApiLoggingProperties(
    Boolean enabled,
    Boolean logBodies,
    Integer maxBodyBytes,
    List<String> excludePathPrefixes,
    List<String> redactJsonFields) {

  public ApiLoggingProperties {
    if (enabled == null) enabled = true;
    if (logBodies == null) logBodies = true;
    if (maxBodyBytes == null) maxBodyBytes = 10_240; // 10KB per body
    if (excludePathPrefixes == null || excludePathPrefixes.isEmpty()) {
      excludePathPrefixes = List.of("/actuator");
    }
    if (redactJsonFields == null || redactJsonFields.isEmpty()) {
      redactJsonFields =
          List.of("password", "newPassword", "oldPassword", "refreshToken", "accessToken", "token", "secret");
    }
  }
}
