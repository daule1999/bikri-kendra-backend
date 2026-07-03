package com.vy.printer.printer_service.scheduler;

import com.vy.printer.printer_service.repository.PrintJobRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The browser (Station Mode) is the worker. This is the ONLY backend scheduled job: it re-queues
 * jobs that were claimed but never reported terminal (browser crash / closed tab) so they don't
 * stall forever. Bounded by max_attempts (R7).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleJobReaper {

  private final PrintJobRepository jobRepo;

  @Value("${printer.reaper.stale-seconds:120}")
  private long staleSeconds;

  @Scheduled(fixedDelayString = "${printer.reaper.fixed-delay-ms:30000}")
  public void reap() {
    LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleSeconds);
    jobRepo
        .reapStale(cutoff)
        .subscribe(
            n -> {
              if (n != null && n > 0) log.warn("Reaped {} stale PROCESSING job(s)", n);
            },
            err -> log.error("Reaper error: {}", err.getMessage()));
  }
}
