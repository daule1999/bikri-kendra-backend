package com.vy.sales.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@EnableConfigurationProperties(AuthUserServiceProperties.class)
@Configuration
public class AppConfig {}
