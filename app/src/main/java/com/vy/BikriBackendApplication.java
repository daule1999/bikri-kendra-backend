package com.vy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bikri Kendra modular monolith — one JVM serving what used to be six microservices.
 *
 * <p>Main class lives in {@code com.vy} so that component scan, R2DBC repository scan and entity
 * scan all cover every module package by default: {@code com.vy.sales.*} (auth, user, inventory,
 * sales, billing) and {@code com.vy.printer.*} (printer).
 *
 * <p>All original REST paths (/api/auth-svc/**, /api/sales-svc/**, …) are preserved, so the
 * frontend and the Next.js proxy routes work unchanged — just point API_BASE_URL at this app's
 * port instead of Traefik.
 */
@SpringBootApplication
public class BikriBackendApplication {
  public static void main(String[] args) {
    SpringApplication.run(BikriBackendApplication.class, args);
  }
}
