package com.vy.sales.user.bootstrap;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;

@Component
@RequiredArgsConstructor
@Slf4j
public class BootstrapOrchestrator {

  private final BootstrapApplication bootstrapApplication;

  @EventListener(ApplicationReadyEvent.class)
  public void bootstrapAll() {

    Retry retrySpec =
        Retry.backoff(3, Duration.ofSeconds(5))
            .filter(e -> true) // retry for any error
            .onRetryExhaustedThrow((rs, signal) -> signal.failure());

    bootstrapApplication
        .bootstrapPermissions()
        .retryWhen(retrySpec)
        .then(bootstrapApplication.bootstrapRoles().retryWhen(retrySpec))
        .then(bootstrapApplication.bootstrapRolesAndPermissions().retryWhen(retrySpec))
        .then(bootstrapApplication.createAdminIfNotExists().retryWhen(retrySpec))
        .doOnSuccess(v -> log.info("All bootstrap completed successfully"))
        .doOnError(e -> log.error("Bootstrap failed after retries", e))
        .subscribe(); // single subscription
  }
}
