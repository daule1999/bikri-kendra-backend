package com.vy.sales.platform.config;

import org.springframework.context.annotation.Configuration;

/**
 * Redis is configured via application.yaml (spring.data.redis.*). Spring Boot auto-configures
 * ReactiveStringRedisTemplate (ReactiveRedisTemplate<String,String>) which is injected into
 * services by type.
 */
@Configuration
public class RedisConfig {}
