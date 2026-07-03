package com.vy.printer.printer_service.controller;

import com.vy.printer.printer_service.dto.ConnectionDto;
import com.vy.printer.printer_service.dto.RegisterPrinterRequest;
import com.vy.printer.printer_service.model.PrintJob;
import com.vy.printer.printer_service.model.Printer;
import com.vy.printer.printer_service.service.PrintJobService;
import com.vy.printer.printer_service.service.PrinterService;
import com.vy.printer.printer_service.util.AppConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/printer-svc/printers")
@RequiredArgsConstructor
public class PrinterController {

  private final PrinterService printerService;
  private final PrintJobService jobService;

  /** Registry — any authenticated user can see every printer (read-all). */
  @GetMapping
  public Flux<Printer> list(
      @RequestParam(value = "agentId", required = false) String agentId,
      @RequestParam(value = "enabledOnly", required = false) Boolean enabledOnly) {
    return printerService.listAll(agentId, enabledOnly);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<Printer> register(
      @Valid @RequestBody RegisterPrinterRequest req,
      // G3: identity optional — gateway injects it, but local dev may omit it.
      @RequestHeader(value = AppConstants.X_USER_ID, required = false) Long userId) {
    return printerService.register(req, userId);
  }

  @PutMapping("/{id}/enable")
  public Mono<Printer> enable(
      @PathVariable Long id,
      @RequestHeader(value = AppConstants.X_USER_ID, required = false) Long userId) {
    return printerService.setEnabled(id, true, userId);
  }

  @PutMapping("/{id}/disable")
  public Mono<Printer> disable(
      @PathVariable Long id,
      @RequestHeader(value = AppConstants.X_USER_ID, required = false) Long userId) {
    return printerService.setEnabled(id, false, userId);
  }

  /** Live queue for the polling UI (active jobs, no payload). */
  @GetMapping("/{id}/queue")
  public Flux<PrintJob> queue(@PathVariable Long id) {
    return jobService.queueForPrinter(id);
  }

  /** "Who's using my printers" — submitters (username, device, job count, last activity). */
  @GetMapping("/connections")
  public Flux<ConnectionDto> connections(@RequestParam("agentId") String agentId) {
    return jobService.connections(agentId);
  }

  /** Disconnect (unregister) a printer from the central system. ?force=true cancels active jobs. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> unregister(
      @PathVariable Long id,
      @RequestParam(value = "force", defaultValue = "false") boolean force,
      @RequestHeader(value = AppConstants.X_USER_ID, required = false) Long userId) {
    return printerService.unregister(id, userId, force);
  }
}
