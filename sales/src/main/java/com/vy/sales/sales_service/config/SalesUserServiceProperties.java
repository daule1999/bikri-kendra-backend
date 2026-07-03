package com.vy.sales.sales_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "services.user")
public class SalesUserServiceProperties {
  private String baseUrl = "http://user-service:8084";
}
