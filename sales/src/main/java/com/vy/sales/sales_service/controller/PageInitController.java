package com.vy.sales.sales_service.controller;

import com.vy.sales.sales_service.service.PageInitService;
import com.vy.sales.sales_service.util.AppConstants;
import com.vy.sales.sales_service.util.PageInitKey;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Generic page-init aggregator.
 *
 * <pre>
 * GET /api/sales-svc/page-init?include=categories,products,users
 * </pre>
 *
 * <p>The {@code include} param is a comma-separated list of {@link PageInitKey} constants. Unknown
 * keys are silently dropped; a 400 is returned only if no valid keys remain.
 */
@RestController
@RequestMapping("/api/sales-svc/page-init")
@RequiredArgsConstructor
@Slf4j
public class PageInitController {

  private final PageInitService pageInitService;

  @GetMapping
  public Mono<Map<String, Object>> getPageInit(
      @RequestParam String include,
      @RequestHeader(AppConstants.X_EVENT_ID) Long eventId,
      @RequestHeader("Authorization") String auth) {

    // Split comma-separated string: "categories,products,users" → ["categories","products","users"]
    Set<String> requested =
        Arrays.stream(include.split(","))
            .map(String::trim)
            .filter(PageInitKey.VALID::contains)
            .collect(Collectors.toSet());

    if (requested.isEmpty()) {
      log.warn("PAGE_INIT_BAD_REQUEST eventId={} include={}", eventId, include);
      return Mono.error(
          new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "No valid keys in 'include'. Valid keys: " + PageInitKey.VALID));
    }

    log.info("PAGE_INIT_REQUEST eventId={} keys={}", eventId, requested);
    return pageInitService.fetch(requested, eventId, auth);
  }
}
