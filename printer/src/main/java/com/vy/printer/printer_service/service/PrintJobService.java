package com.vy.printer.printer_service.service;

import com.vy.printer.printer_service.dto.CreatePrintJobRequest;
import com.vy.printer.printer_service.model.PrintJob;
import com.vy.printer.printer_service.model.Printer;
import com.vy.printer.printer_service.repository.PrintJobRepository;
import com.vy.printer.printer_service.repository.PrinterRepository;
import com.vy.printer.printer_service.util.AppConstants.JobStatus;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrintJobService {

  private final PrintJobRepository jobRepo;
  private final PrinterRepository printerRepo;

  @Value("${printer.job.priority-own:10}")
  private int priorityOwn;

  @Value("${printer.job.priority-normal:100}")
  private int priorityNormal;

  @Value("${printer.job.max-payload-bytes:262144}")
  private int maxPayloadBytes;

  /**
   * Enqueue a job. Resolves the target printer (explicit → submitter default → global default),
   * computes own-billing priority (G6), and dedupes via idempotency key (G4).
   */
  public Mono<PrintJob> enqueue(CreatePrintJobRequest req, Long userId, String username) {
    if (req.getEscposBase64() == null
        || req.getEscposBase64().getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
      return Mono.error(
          new IllegalArgumentException("escposBase64 missing or exceeds payload cap"));
    }

    // Idempotency: if the same key already exists, return the existing row (no double insert).
    Mono<PrintJob> existing =
        (req.getIdempotencyKey() == null || req.getIdempotencyKey().isBlank())
            ? Mono.empty()
            : jobRepo.findByIdempotencyKey(req.getIdempotencyKey());

    return existing.switchIfEmpty(
        Mono.defer(
            () ->
                resolvePrinter(req)
                    .flatMap(
                        printer -> {
                          int priority = resolvePriority(req, printer, userId);
                          PrintJob job =
                              PrintJob.builder()
                                  .printerId(printer.getId())
                                  .invoiceId(req.getInvoiceId())
                                  .invoiceNo(req.getInvoiceNo())
                                  .orderNo(req.getOrderNo())
                                  .eventId(req.getEventId())
                                  .jobType(req.getJobType() == null ? "INVOICE" : req.getJobType())
                                  .status(JobStatus.QUEUED)
                                  .priority(priority)
                                  .escposBase64(req.getEscposBase64())
                                  .receiptSnapshot(req.getReceiptSnapshot())
                                  .idempotencyKey(req.getIdempotencyKey())
                                  .submittedBy(userId)
                                  .submittedUsername(username)
                                  .submittedAgent(req.getSubmittedAgent())
                                  .attempts(0)
                                  .maxAttempts(3)
                                  .createdAt(java.time.LocalDateTime.now())
                                  .updatedAt(java.time.LocalDateTime.now())
                                  .build();
                          return jobRepo.save(job);
                        })
                    .doOnSuccess(
                        j ->
                            log.info(
                                "Job queued id={} printer={} priority={} agent={}",
                                j.getId(),
                                j.getPrinterId(),
                                j.getPriority(),
                                j.getSubmittedAgent()))
                    // I2: lost the insert race on the unique idempotency key → return the row the
                    // winner created instead of bubbling a 500.
                    .onErrorResume(
                        DataIntegrityViolationException.class,
                        e ->
                            (req.getIdempotencyKey() == null)
                                ? Mono.error(e)
                                : jobRepo.findByIdempotencyKey(req.getIdempotencyKey()))));
  }

  private Mono<Printer> resolvePrinter(CreatePrintJobRequest req) {
    if (req.getPrinterId() != null) {
      return printerRepo
          .findById(req.getPrinterId())
          .switchIfEmpty(Mono.error(new IllegalArgumentException("printer not found")));
    }
    Mono<Printer> agentDefault =
        req.getSubmittedAgent() == null
            ? Mono.empty()
            : printerRepo.findDefaultForAgent(req.getSubmittedAgent());
    return agentDefault
        .switchIfEmpty(printerRepo.findGlobalDefault())
        .switchIfEmpty(
            Mono.error(new IllegalArgumentException("no printerId and no default printer")));
  }

  /** G6/I5: own billing = submitter agent OR same owning user as the target printer. */
  private int resolvePriority(CreatePrintJobRequest req, Printer printer, Long callerUserId) {
    if (req.getPriority() != null) return req.getPriority();
    boolean ownByAgent =
        req.getSubmittedAgent() != null
            && Objects.equals(req.getSubmittedAgent(), printer.getOwnerAgentId());
    boolean ownByUser =
        callerUserId != null && Objects.equals(callerUserId, printer.getOwnerUserId());
    return (ownByAgent || ownByUser) ? priorityOwn : priorityNormal;
  }

  /** "Who's using my printer" — submitters for printers owned by this device. */
  public reactor.core.publisher.Flux<com.vy.printer.printer_service.dto.ConnectionDto> connections(
      String agentId) {
    return jobRepo.findConnectionsForOwner(agentId);
  }

  // ── Station Mode ──────────────────────────────────────────────────────────
  public Flux<PrintJob> claimable(String agentId, int max) {
    return jobRepo.findClaimable(agentId, max).map(PrintJobService::stripPayload);
  }

  /** Atomic claim; returns the FULL job (with escpos) only if this caller won the race. */
  public Mono<PrintJob> claim(Long id, String agentId) {
    return jobRepo
        .claim(id, agentId)
        .flatMap(rows -> rows == 1 ? jobRepo.findById(id) : Mono.empty());
  }

  public Mono<PrintJob> complete(Long id) {
    return jobRepo
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("job not found")))
        .flatMap(
            j -> {
              j.setStatus(JobStatus.DONE);
              j.setErrorMessage(null);
              j.setUpdatedAt(java.time.LocalDateTime.now());
              return jobRepo.save(j);
            })
        .map(PrintJobService::stripPayload);
  }

  /** Fail with bounded retry: re-queue until attempts ≥ max, then terminal FAILED (R7). */
  public Mono<PrintJob> fail(Long id, String error) {
    return jobRepo
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("job not found")))
        .flatMap(
            j -> {
              boolean exhausted = j.getAttempts() != null && j.getAttempts() >= j.getMaxAttempts();
              j.setStatus(exhausted ? JobStatus.FAILED : JobStatus.QUEUED);
              j.setClaimedBy(null);
              j.setClaimedAt(null);
              j.setErrorMessage(error);
              j.setUpdatedAt(java.time.LocalDateTime.now());
              return jobRepo.save(j);
            })
        .map(PrintJobService::stripPayload);
  }

  public Mono<PrintJob> cancel(Long id, Long callerUserId) {
    return jobRepo
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("job not found")))
        .flatMap(j -> requireJobAccess(j, callerUserId))
        .flatMap(
            j -> {
              j.setStatus(JobStatus.CANCELLED);
              j.setUpdatedAt(java.time.LocalDateTime.now());
              return jobRepo.save(j);
            })
        .map(PrintJobService::stripPayload);
  }

  /**
   * I1: a job may be cancelled/reprinted by the user who submitted it OR the user who owns the
   * target printer. Lenient when caller or owner identity is unknown (dev / legacy rows).
   */
  private Mono<PrintJob> requireJobAccess(PrintJob j, Long userId) {
    if (userId == null || Objects.equals(userId, j.getSubmittedBy())) return Mono.just(j);
    return printerRepo
        .findById(j.getPrinterId())
        .flatMap(
            p -> {
              if (p.getOwnerUserId() == null || Objects.equals(userId, p.getOwnerUserId())) {
                return Mono.just(j);
              }
              return Mono.<PrintJob>error(
                  new ResponseStatusException(HttpStatus.FORBIDDEN, "not allowed on this job"));
            })
        .switchIfEmpty(Mono.just(j));
  }

  /**
   * Reprint (G4): re-enqueue the SAME row's payload as a brand-new job with a fresh idempotency
   * key, so the unique constraint never blocks a legitimate reprint.
   */
  public Mono<PrintJob> reprint(Long id, Long callerUserId) {
    return jobRepo
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("job not found")))
        .flatMap(j -> requireJobAccess(j, callerUserId))
        .flatMap(
            src -> {
              PrintJob copy =
                  PrintJob.builder()
                      .printerId(src.getPrinterId())
                      .invoiceId(src.getInvoiceId())
                      .invoiceNo(src.getInvoiceNo())
                      .orderNo(src.getOrderNo())
                      .eventId(src.getEventId())
                      .jobType("REPRINT")
                      .status(JobStatus.QUEUED)
                      .priority(src.getPriority())
                      .escposBase64(src.getEscposBase64())
                      .receiptSnapshot(src.getReceiptSnapshot())
                      .idempotencyKey(
                          (src.getIdempotencyKey() == null
                                  ? "job-" + src.getId()
                                  : src.getIdempotencyKey())
                              + ":reprint:"
                              + System.currentTimeMillis())
                      .submittedBy(src.getSubmittedBy())
                      .submittedAgent(src.getSubmittedAgent())
                      .attempts(0)
                      .maxAttempts(3)
                      .createdAt(java.time.LocalDateTime.now())
                      .updatedAt(java.time.LocalDateTime.now())
                      .build();
              return jobRepo.save(copy);
            });
  }

  // ── Views ──────────────────────────────────────────────────────────────────
  public Flux<PrintJob> queueForPrinter(Long printerId) {
    return jobRepo.findActiveByPrinter(printerId).map(PrintJobService::stripPayload);
  }

  public Mono<PrintJob> get(Long id) {
    return jobRepo
        .findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("job not found")))
        .map(PrintJobService::stripPayload);
  }

  /** Never ship the heavy ESC/POS blob in list/status responses — only on a won claim. */
  private static PrintJob stripPayload(PrintJob j) {
    j.setEscposBase64(null);
    j.setReceiptSnapshot(null);
    return j;
  }
}
