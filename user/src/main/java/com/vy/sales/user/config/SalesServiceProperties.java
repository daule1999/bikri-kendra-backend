package com.vy.sales.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "services.sales")
@Getter
@Setter
@Configuration
public class SalesServiceProperties {
  private String baseUrl;
}
