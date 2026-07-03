package com.vy.sales.platform.config;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.stereotype.Component;

/**
 * Fails fast at startup if the JVM timezone is not Asia/Kolkata.
 *
 * <p>This guards against a partial deployment where -Duser.timezone=Asia/Kolkata is missing from
 * the Dockerfile ENTRYPOINT while application.yaml already sets serverTimezone=Asia/Kolkata for
 * MySQL. That combination would cause LocalDateTime.now() (UTC) values to be stored incorrectly in
 * TIMESTAMP columns.
 */
@Component
public class TimezoneValidationBean {

  @PostConstruct
  public void validateTimezone() {
    String jvmTz = TimeZone.getDefault().getID();
    if (!"Asia/Kolkata".equals(jvmTz)) {
      throw new IllegalStateException(
          "JVM timezone must be Asia/Kolkata but is: "
              + jvmTz
              + ". Add -Duser.timezone=Asia/Kolkata to the Dockerfile ENTRYPOINT.");
    }
  }
}
