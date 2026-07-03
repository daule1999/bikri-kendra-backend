package com.vy.printer.printer_service.controller;

import com.vy.printer.printer_service.dto.ClaimRequest;
import com.vy.printer.printer_service.dto.CreatePrintJobRequest;
import com.vy.printer.printer_service.dto.FailRequest;
import com.vy.printer.printer_service.model.PrintJob;
import com.vy.printer.printer_service.service.PrintJobService;
import com.vy.printer.printer_service.util.AppConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/printer-svc/print-jobs")
@RequiredArgsConstructor
public class PrintJobController {

  private final PrintJobService jobService;

  /** Submit (Sender B). Pre-rendered ESC/POS in the body. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<PrintJob> create(
      @Valid @RequestBody CreatePrintJobRequest req,
      @RequestHeader(value = AppConstants.X_USER_ID, required = false) Long userId,
      @RequestHeader(value = AppConstants.X_USERNAME, required = false) String username) {
    return jobService.enqueue(req, userId, username);
  }

  /** Station Mode poll — claimable jobs for printers this agent owns (priority-FIFO). */
  @GetMapping("/claimable")
  public Flux<PrintJob> claimable(
      @RequestParam("agentId") String agentId,
      @RequestParam(value = "max", defaultValue = "1") int max) {
    return jobService.claimable(agentId, max);
  }

  /** Atomic claim. 200 + full job (with escpos) if won; 409 if another tab/station took it. */
  @PostMapping("/{id}/claim")
  public Mono<ResponseEntity<PrintJob>> claim(
      @PathVariable Long id, @Valid @RequestBody ClaimRequest req) {
    return jobService
        .claim(id, req.getAgentId())
        .map(ResponseEntity::ok)
        .switchIfEmpty(Mono.just(ResponseEntity.<PrintJob>status(HttpStatus.CONFLICT).build()));
  }

  @PostMapping("/{id}/complete")
  public Mono<PrintJob> complete(@PathVariable Long id) {
    return jobService.complete(id);
  }

  @PostMapping("/{id}/fail")
  public Mono<PrintJob> fail(
      @PathVariable Long id, @RequestBody(required = false) FailRequest req) {
    return jobService.fail(id, req == null ? "unknown" : req.getError());
  }

  @GetMapping("/{id}")
  public Mono<PrintJob> get(@PathVariable Long id) {
    return jobService.get(id);
  }

  @PostMapping("/{id}/cancel")
  public Mono<PrintJob> cancel(
      @PathVariable Long id,
      @RequestHeader(value = AppConstants.X_USER_ID, required = false) Long userId) {
    return jobService.cancel(id, userId);
  }

  @PostMapping("/{id}/reprint")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<PrintJob> reprint(
      @PathVariable Long id,
      @RequestHeader(value = AppConstants.X_USER_ID, required = false) Long userId) {
    return jobService.reprint(id, userId);
  }
}
