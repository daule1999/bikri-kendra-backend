package com.vy.sales.sales_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "services.billing")
@Getter
@Setter
@Configuration
public class BillingServiceProperties {
  private String baseUrl;
}
