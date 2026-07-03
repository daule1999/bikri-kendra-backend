package com.vy.sales.user.client;

import com.vy.sales.user.config.SalesServiceProperties;
import com.vy.sales.user.dto.UserRegistrationRequest;
import com.vy.sales.user.entity.User;
import com.vy.sales.user.mapper.Mapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class SalesClient {
  private final WebClient.Builder webClientBuilder;
  private final SalesServiceProperties salesServiceProperties;
  private final Mapper mapper;

  public Mono<?> postForRequest(
      Object request, UserRegistrationRequest userRegistrationRequest, User user, String uri) {
    long startTime = System.currentTimeMillis();

    return webClientBuilder
        .build()
        .post()
        .uri(uri)
        .bodyValue(request)
        .retrieve()
        .bodyToMono(Object.class)
        .doOnSuccess(
            response ->
                log.info(
                    "Post for uri={} successfully done in {} ms",
                    uri,
                    System.currentTimeMillis() - startTime))
        .doOnError(
            ex ->
                log.error(
                    "Post for uri={} failed in {} ms",
                    uri,
                    System.currentTimeMillis() - startTime,
                    ex));
  }

  public Mono<Boolean> hasAnyEvent() {
    String url = salesServiceProperties.getBaseUrl() + "/api/sales-svc/events/exists";
    log.info("Checking if any event exists via URL: {}", url);
    return webClientBuilder
        .build()
        .get()
        .uri(url)
        .retrieve()
        .bodyToMono(Boolean.class)
        .onErrorResume(
            ex -> {
              log.error(
                  "Failed to check if any event exists in sales-service, defaulting to true to avoid lockout",
                  ex);
              return Mono.just(true);
            });
  }
}
