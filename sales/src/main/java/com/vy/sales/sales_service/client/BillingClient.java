package com.vy.sales.sales_service.client;

import com.vy.sales.sales_service.config.BillingServiceProperties;
import com.vy.sales.sales_service.dto.BillingInvoiceRequest;
import com.vy.sales.sales_service.dto.BillingInvoiceResponse;
import com.vy.sales.sales_service.dto.BillingReturnRequest;
import com.vy.sales.sales_service.mapper.BillingInvoiceMapper;
import com.vy.sales.sales_service.util.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingClient {

  private final WebClient.Builder webClientBuilder;
  private final BillingServiceProperties properties;
  private static final String billingInvoices = "/api/billing-svc/invoices";
  private final BillingInvoiceMapper billingInvoiceMapper;

  public Mono<BillingInvoiceResponse> createInvoice(
      BillingInvoiceRequest request, Long userId, Long eventId) {
    log.info("Request to Billing service= {}", request);

    return webClientBuilder
        .build()
        .post()
        .uri(properties.getBaseUrl() + billingInvoices)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .bodyValue(billingInvoiceMapper.toBillingRequest(request, userId))
        .retrieve()
        .bodyToMono(BillingInvoiceResponse.class);
  }

  public Mono<Void> cancelInvoice(
      String orderNumber, String reason, Long eventId, String authHeader) {
    log.info("Request to cancel invoice orderNumber={} reason={}", orderNumber, reason);
    return webClientBuilder
        .build()
        .put()
        .uri(
            properties.getBaseUrl()
                + billingInvoices
                + "/order/"
                + orderNumber
                + "/cancel?reason="
                + reason)
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .retrieve()
        .bodyToMono(Void.class);
  }

  /**
   * Saga compensation: reinstate a previously cancelled invoice back to PAID. Called when the final
   * {@code orderRepo.save(CANCELLED)} in cancelSale fails, so billing stays consistent with the
   * order's still-CONFIRMED status.
   */
  public Mono<Void> reinstateInvoice(String orderNumber, Long eventId, String authHeader) {
    log.info("Request to reinstate invoice orderNumber={}", orderNumber);
    return webClientBuilder
        .build()
        .put()
        .uri(properties.getBaseUrl() + billingInvoices + "/order/" + orderNumber + "/reinstate")
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .retrieve()
        .bodyToMono(Void.class);
  }

  public Mono<Void> returnInvoice(
      String orderNumber, BillingReturnRequest request, Long eventId, String authHeader) {
    log.info("Request to process return for invoice orderNumber={}", orderNumber);
    return webClientBuilder
        .build()
        .put()
        .uri(properties.getBaseUrl() + billingInvoices + "/order/" + orderNumber + "/return")
        .header("Authorization", authHeader)
        .header(AppConstants.X_EVENT_ID, String.valueOf(eventId))
        .bodyValue(request)
        .retrieve()
        .bodyToMono(Void.class);
  }
}
