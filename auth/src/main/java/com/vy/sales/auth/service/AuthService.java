package com.vy.sales.auth.service;

import com.vy.sales.auth.client.UserServiceClient;
import com.vy.sales.auth.client.dto.AuthValidationRequest;
import com.vy.sales.auth.client.dto.AuthValidationResponse;
import com.vy.sales.auth.dto.AuthRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

  private final UserServiceClient userServiceClient;

  public Mono<AuthValidationResponse> validateUser(AuthRequest authRequest) {
    AuthValidationRequest validationRequest =
        AuthValidationRequest.builder()
            .username(authRequest.getUsername())
            .password(authRequest.getPassword())
            .build();
    return userServiceClient.validateUser(validationRequest);
  }
}
