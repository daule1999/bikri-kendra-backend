package com.vy.sales.auth.scheduler;

import com.vy.sales.auth.repository.AuthSessionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps auth_sessions from growing unbounded — every login/refresh writes a row (see
 * SessionStoreService), so this must exist from day one, not as a follow-up. Deletes rows that
 * expired more than {@code grace-hours} ago (grace window purely so a row doesn't vanish the
 * instant it's no longer valid, in case anything ever wants to inspect "recently expired"
 * sessions for debugging).
 *
 * <p>Safe to run even when the session store is disabled (security.session-store.enabled=false)
 * — the table will simply be empty and this is a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSessionCleanupJob {

  private final AuthSessionRepository authSessionRepository;

  @Value("${security.session-store.cleanup.grace-hours:24}")
  private long graceHours;

  @Scheduled(cron = "${security.session-store.cleanup.cron:0 0 2 * * *}") // 2 AM daily by default
  public void cleanup() {
    LocalDateTime cutoff = LocalDateTime.now().minusHours(graceHours);
    authSessionRepository
        .deleteExpiredBefore(cutoff)
        .subscribe(
            n -> {
              if (n != null && n > 0) log.info("AUTH_SESSION_CLEANUP deleted={} cutoff={}", n, cutoff);
            },
            err -> log.error("AUTH_SESSION_CLEANUP_ERROR reason={}", err.getMessage()));
  }
}
