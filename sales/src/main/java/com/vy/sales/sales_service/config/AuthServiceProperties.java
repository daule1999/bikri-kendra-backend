package com.vy.sales.sales_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "services.auth")
public class AuthServiceProperties {
  private String baseUrl = "http://auth-service:8084";
}
