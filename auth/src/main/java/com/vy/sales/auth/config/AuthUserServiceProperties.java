package com.vy.sales.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.user")
@Getter
@Setter
public class AuthUserServiceProperties {
  private String baseUrl;
}
