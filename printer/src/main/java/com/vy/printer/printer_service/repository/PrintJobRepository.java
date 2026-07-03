package com.vy.printer.printer_service.repository;

import com.vy.printer.printer_service.dto.ConnectionDto;
import com.vy.printer.printer_service.model.PrintJob;
import java.time.LocalDateTime;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface PrintJobRepository extends ReactiveCrudRepository<PrintJob, Long> {

  // ── Station Mode poll: jobs for printers this agent owns, priority then strict FIFO (G2) ──
  // I9: select only light columns — the heavy escpos_base64/receipt_snapshot are fetched on claim.
  @Query(
      """
      SELECT j.id, j.printer_id, j.invoice_id, j.invoice_no, j.order_no, j.event_id,
             j.job_type, j.status, j.priority, j.idempotency_key, j.submitted_by,
             j.submitted_agent, j.claimed_by, j.claimed_at, j.attempts, j.max_attempts,
             j.error_message, j.created_at, j.updated_at
      FROM print_jobs j
      JOIN printers p ON p.id = j.printer_id
      WHERE p.owner_agent_id = :agentId
        AND p.enabled = TRUE
        AND j.status = 'QUEUED'
      ORDER BY j.priority ASC, j.created_at ASC, j.id ASC
      LIMIT :max
      """)
  Flux<PrintJob> findClaimable(@Param("agentId") String agentId, @Param("max") int max);

  // ── Atomic claim: wins only if still QUEUED *and* its printer is still enabled (G9) ──
  @Modifying
  @Query(
      """
      UPDATE print_jobs j
      JOIN printers p ON p.id = j.printer_id
         SET j.status = 'PROCESSING',
             j.claimed_by = :agentId,
             j.claimed_at = NOW(3),
             j.attempts = j.attempts + 1
       WHERE j.id = :id
         AND j.status = 'QUEUED'
         AND p.enabled = TRUE
      """)
  Mono<Integer> claim(@Param("id") Long id, @Param("agentId") String agentId);

  // ── Queue view (polling UI): active jobs for a printer ──
  @Query(
      """
      SELECT * FROM print_jobs
      WHERE printer_id = :printerId
        AND status IN ('QUEUED','PROCESSING')
      ORDER BY priority ASC, created_at ASC, id ASC
      """)
  Flux<PrintJob> findActiveByPrinter(@Param("printerId") Long printerId);

  Mono<PrintJob> findByIdempotencyKey(String idempotencyKey);

  // ── disconnect validation: how many jobs are still in flight for this printer ──
  @Query(
      "SELECT COUNT(*) FROM print_jobs WHERE printer_id = :printerId AND status IN ('QUEUED','PROCESSING')")
  Mono<Long> countActiveForPrinter(@Param("printerId") Long printerId);

  // ── "who's using my printer": submitters grouped per (user × device × printer) I own ──
  @Query(
      """
      SELECT j.submitted_username AS submitted_username,
             j.submitted_by       AS submitted_by,
             j.submitted_agent    AS submitted_agent,
             j.printer_id         AS printer_id,
             p.name               AS printer_name,
             COUNT(*)             AS job_count,
             MAX(j.created_at)    AS last_job_at,
             SUBSTRING_INDEX(GROUP_CONCAT(j.status ORDER BY j.created_at DESC), ',', 1) AS last_status
      FROM print_jobs j
      JOIN printers p ON p.id = j.printer_id
      WHERE p.owner_agent_id = :agentId
      GROUP BY j.submitted_username, j.submitted_by, j.submitted_agent, j.printer_id, p.name
      ORDER BY last_job_at DESC
      """)
  Flux<ConnectionDto> findConnectionsForOwner(@Param("agentId") String agentId);

  // ── disable-cascade: cancel everything still queued for a printer (R6) ──
  @Modifying
  @Query(
      "UPDATE print_jobs SET status='CANCELLED', error_message='printer disabled' "
          + "WHERE printer_id = :printerId AND status='QUEUED'")
  Mono<Integer> cancelQueuedForPrinter(@Param("printerId") Long printerId);

  // ── reaper: re-queue stale PROCESSING jobs that never reported terminal (G7/R7) ──
  @Modifying
  @Query(
      """
      UPDATE print_jobs
         SET status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
             error_message = 'reaped: stale PROCESSING'
       WHERE status = 'PROCESSING'
         AND claimed_at < :cutoff
      """)
  Mono<Integer> reapStale(@Param("cutoff") LocalDateTime cutoff);

  // ── Disconnect cascade: purge ALL job history for a printer before deleting it ──
  // The DB FK (V4 migration) also cascades, but explicit deletion here ensures
  // the printer can be removed even if the migration has not yet run.
  @Modifying
  @Query("DELETE FROM print_jobs WHERE printer_id = :printerId")
  Mono<Integer> deleteAllByPrinterId(@Param("printerId") Long printerId);
}
