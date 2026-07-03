package com.vy.printer.printer_service.service;

import com.vy.printer.printer_service.dto.RegisterPrinterRequest;
import com.vy.printer.printer_service.model.Printer;
import com.vy.printer.printer_service.repository.PrintJobRepository;
import com.vy.printer.printer_service.repository.PrinterRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterService {

  private final PrinterRepository printerRepo;
  private final PrintJobRepository jobRepo;

  /** Registry view — everyone can see every printer (read-all). escpos never lives here. */
  public Flux<Printer> listAll(String agentIdFilter, Boolean enabledOnly) {
    Flux<Printer> base =
        (agentIdFilter != null && !agentIdFilter.isBlank())
            ? printerRepo.findByOwnerAgentId(agentIdFilter)
            : printerRepo.findAll();
    if (Boolean.TRUE.equals(enabledOnly)) {
      return base.filter(p -> Boolean.TRUE.equals(p.getEnabled()));
    }
    return base;
  }

  public Mono<Printer> register(RegisterPrinterRequest req, Long userId) {
    Printer p =
        Printer.builder()
            .name(req.getName())
            .qzPrinterName(req.getQzPrinterName())
            .location(req.getLocation())
            .ownerAgentId(req.getOwnerAgentId())
            .ownerUserId(userId) // G6: stable identity
            .ownerLabel(req.getOwnerLabel())
            .hostname(req.getHostname())
            .connectionType(req.getConnectionType() == null ? "qz" : req.getConnectionType())
            .driverInfo(req.getDriverInfo())
            .enabled(false) // becomes true only after a successful test print
            .isDefault(Boolean.TRUE.equals(req.getIsDefault()))
            .eventId(req.getEventId())
            .createdBy(userId)
            .createdAt(java.time.LocalDateTime.now())
            .updatedAt(java.time.LocalDateTime.now())
            .build();
    return printerRepo
        .save(p)
        .doOnSuccess(
            saved -> log.info("Printer registered id={} name={}", saved.getId(), saved.getName()));
  }

  public Mono<Printer> setEnabled(Long id, boolean enabled, Long callerUserId) {
    return printerRepo
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("printer not found: " + id)))
        .flatMap(
            p -> {
              // I1: only the owning user may enable/disable a printer.
              requireOwner(callerUserId, p.getOwnerUserId(), "printer");
              p.setEnabled(enabled);
              p.setUpdatedAt(java.time.LocalDateTime.now());
              Mono<Printer> saved = printerRepo.save(p);
              // R6: disabling cancels everything still queued for this printer
              return enabled ? saved : jobRepo.cancelQueuedForPrinter(id).then(saved);
            });
  }

  /**
   * I1 ownership guard. Strict when both identities are known (blocks user A touching user B's
   * resource → 403). Lenient when caller identity or owner is null (local dev without the gateway,
   * or legacy rows) so it doesn't break non-gateway runs. In production Traefik always injects
   * X-User-Id, so the strict branch is the one that fires.
   */
  static void requireOwner(Long callerUserId, Long ownerUserId, String what) {
    if (callerUserId != null && ownerUserId != null && !Objects.equals(callerUserId, ownerUserId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not the owner of this " + what);
    }
  }

  public Mono<Printer> getById(Long id) {
    return printerRepo
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("printer not found: " + id)));
  }

  /**
   * Disconnect a printer from the central system (unregister). Validation: - only the owner may
   * disconnect (I1); - blocked while jobs are still QUEUED/PROCESSING for it (force=true overrides
   * by cancelling those jobs first), so a busy printer is never yanked mid-queue.
   */
  public Mono<Void> unregister(Long id, Long callerUserId, boolean force) {
    return printerRepo
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("printer not found: " + id)))
        .flatMap(
            p -> {
              requireOwner(callerUserId, p.getOwnerUserId(), "printer");
              return jobRepo
                  .countActiveForPrinter(id)
                  .flatMap(
                      active -> {
                        if (active != null && active > 0 && !force) {
                          return Mono.error(
                              new ResponseStatusException(
                                  HttpStatus.CONFLICT,
                                  active
                                      + " job(s) still queued/printing — clear or force-disconnect"));
                        }
                        // 1. Cancel any still-active jobs (updates status for audit trail).
                        Mono<Void> cancelActive =
                            (active != null && active > 0)
                                ? jobRepo.cancelQueuedForPrinter(id).then()
                                : Mono.empty();
                        // 2. Delete ALL job history for this printer before deleting the printer.
                        //    The FK constraint (fk_job_printer) blocks printer deletion if any
                        //    job rows remain (even DONE/FAILED/CANCELLED). The V4 migration adds
                        //    ON DELETE CASCADE at the DB level, but we also delete explicitly here
                        //    so the operation works before the migration runs.
                        Mono<Void> purgeJobs = jobRepo.deleteAllByPrinterId(id).then();
                        return cancelActive.then(purgeJobs).then(printerRepo.deleteById(id));
                      });
            });
  }
}
