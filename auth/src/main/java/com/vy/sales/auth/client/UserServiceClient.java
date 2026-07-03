package com.vy.sales.auth.client;

import com.vy.sales.auth.client.dto.AuthValidationRequest;
import com.vy.sales.auth.client.dto.AuthValidationResponse;
import com.vy.sales.auth.config.AuthUserServiceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UserServiceClient {
  private final WebClient.Builder webClientBuilder;
  private final AuthUserServiceProperties properties;
  private static final String validateUserApi = "/api/users-svc/validate";

  public Mono<AuthValidationResponse> validateUser(AuthValidationRequest authValidationRequest) {
    return webClientBuilder
        .build()
        .post()
        .uri(properties.getBaseUrl() + validateUserApi)
        .bodyValue(authValidationRequest)
        .retrieve()
        .bodyToMono(AuthValidationResponse.class);
  }
}
