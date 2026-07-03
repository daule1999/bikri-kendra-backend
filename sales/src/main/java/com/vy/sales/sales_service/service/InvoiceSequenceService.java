package com.vy.sales.sales_service.service;

import com.vy.sales.sales_service.model.ShopInvoiceSequence;
import com.vy.sales.sales_service.repository.ShopInvoiceSequenceRepository;
import com.vy.sales.sales_service.repository.ShopRepository;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceSequenceService {

  private final ShopInvoiceSequenceRepository sequenceRepo;
  private final ShopRepository shopRepo;
  private final ConnectionFactory connectionFactory;

  /** Ensure a sequence row exists for the given shop/event. Called when a shift opens. */
  @Transactional
  public Mono<Void> initializeOrUpdateSequence(Long shopId, Long eventId) {
    return shopRepo
        .findById(shopId)
        .flatMap(
            shop -> {
              String categoryName =
                  shop.getCategoryName() != null ? shop.getCategoryName() : "GENERAL";
              return sequenceRepo
                  .findByShopIdAndEventId(shopId, eventId)
                  .switchIfEmpty(
                      Mono.defer(
                          () ->
                              sequenceRepo.save(
                                  new ShopInvoiceSequence(
                                      null, shopId, eventId, categoryName, 0L, null))))
                  .flatMap(
                      seq -> {
                        // keep category name up-to-date in case it changed
                        if (!categoryName.equals(seq.getCategoryName())) {
                          seq.setCategoryName(categoryName);
                          return sequenceRepo.save(seq);
                        }
                        return Mono.just(seq);
                      })
                  .then();
            });
  }

  /**
   * Atomically increments the sequence counter and returns the formatted invoice number.
   *
   * <p>Uses an explicit R2DBC connection (via {@link ConnectionFactory}) so that the UPDATE with
   * {@code LAST_INSERT_ID(next_seq + 1)} and the subsequent {@code SELECT LAST_INSERT_ID()} are
   * guaranteed to execute on the <em>same</em> physical connection. This avoids the race condition
   * where connection-pool reassignment between statements would cause two concurrent callers to
   * observe the same sequence value.
   *
   * @param shopId the shop identifier
   * @param eventId the event identifier
   * @param shiftId the active shift session identifier
   * @return Mono emitting the complete invoice number string
   */
  public Mono<String> claimAndAdvance(Long shopId, Long eventId, Long shiftId) {
    // Fix 2: Block invoice issuance when the shop is closed
    return shopRepo
        .findById(shopId)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Shop not found: " + shopId)))
        .flatMap(
            shop -> {
              if (!Boolean.TRUE.equals(shop.getIsActive())) {
                return Mono.<String>error(
                    new IllegalStateException(
                        "Cannot issue invoice: shop '" + shop.getShopName() + "' is closed."));
              }
              return initializeOrUpdateSequence(shopId, eventId)
                  .then(
                      Mono.usingWhen(
                          Mono.from(connectionFactory.create()),
                          conn -> incrementOnConnection(conn, shopId, eventId, shiftId),
                          conn -> Mono.from(conn.close()),
                          (conn, err) -> Mono.from(conn.close()),
                          conn -> Mono.from(conn.close())));
            });
  }

  /**
   * Returns the invoice number that <em>would</em> be issued on the next call to {@link
   * #claimAndAdvance}, without advancing the sequence counter.
   *
   * <p>Safe to call as many times as needed for display purposes (no side effects).
   *
   * @param shopId the shop identifier
   * @param eventId the event identifier
   * @param shiftId the active shift session identifier
   * @return Mono emitting the formatted preview invoice number, or "" if no sequence row exists
   */
  public Mono<String> peekNextFormatted(Long shopId, Long eventId, Long shiftId) {
    return sequenceRepo
        .findByShopIdAndEventId(shopId, eventId)
        .map(
            seq -> {
              long nextSeq = (seq.getNextSeq() != null ? seq.getNextSeq() : 0) + 1L;
              String catPart =
                  seq.getCategoryName() != null
                      ? seq.getCategoryName().toUpperCase().replaceAll(" ", "_")
                      : "GENERAL";
              return String.format(
                  "SO-%d-%s-%d-%d-%05d", eventId, catPart, shopId, shiftId, nextSeq);
            })
        .defaultIfEmpty("");
  }

  private Mono<String> incrementOnConnection(
      Connection conn, Long shopId, Long eventId, Long shiftId) {

    return Mono.from(conn.beginTransaction())
        // Step 1: Atomic increment — LAST_INSERT_ID(next_seq+1) sets the session variable
        .then(
            Mono.from(
                conn.createStatement(
                        "UPDATE shop_invoice_sequence "
                            + "SET next_seq = LAST_INSERT_ID(next_seq + 1), updated_at = NOW() "
                            + "WHERE shop_id = ? AND event_id = ?")
                    .bind(0, shopId)
                    .bind(1, eventId)
                    .execute()))
        .flatMap(result -> Mono.from(result.getRowsUpdated()))
        .flatMap(
            rowsUpdated -> {
              if (rowsUpdated == 0) {
                // Row not yet initialised — should not happen in normal flow
                return Mono.error(
                    new IllegalStateException(
                        "Invoice sequence not initialised for shopId="
                            + shopId
                            + ", eventId="
                            + eventId
                            + ". Call initializeOrUpdateSequence first."));
              }
              // Step 2: Read back the incremented value AND category_name on the same connection
              return Mono.from(
                  conn.createStatement(
                          "SELECT LAST_INSERT_ID() AS new_seq, category_name "
                              + "FROM shop_invoice_sequence "
                              + "WHERE shop_id = ? AND event_id = ?")
                      .bind(0, shopId)
                      .bind(1, eventId)
                      .execute());
            })
        .flatMap(
            result ->
                Mono.from(
                    result.map(
                        (row, meta) -> {
                          long newSeq = row.get("new_seq", Long.class);
                          String categoryName = row.get("category_name", String.class);
                          String catPart =
                              categoryName != null
                                  ? categoryName.toUpperCase().replaceAll(" ", "_")
                                  : "GENERAL";
                          return String.format(
                              "SO-%d-%s-%d-%d-%05d", eventId, catPart, shopId, shiftId, newSeq);
                        })))
        // Step 3: Persist the formatted invoice number + audit columns
        .flatMap(
            invoiceNo ->
                Mono.from(
                        conn.createStatement(
                                "UPDATE shop_invoice_sequence "
                                    + "SET next_invoice_no = ?, "
                                    + "    last_issued_at = NOW(), "
                                    + "    lock_version = lock_version + 1 "
                                    + "WHERE shop_id = ? AND event_id = ?")
                            .bind(0, invoiceNo)
                            .bind(1, shopId)
                            .bind(2, eventId)
                            .execute())
                    .flatMap(r -> Mono.from(r.getRowsUpdated()))
                    .thenReturn(invoiceNo))
        // Step 4: Commit and return invoice number, rollback on any error
        .flatMap(invoiceNo -> Mono.from(conn.commitTransaction()).thenReturn(invoiceNo))
        .onErrorResume(err -> Mono.from(conn.rollbackTransaction()).then(Mono.error(err)));
  }
}
