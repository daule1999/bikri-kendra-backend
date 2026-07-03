package com.vy.app.config;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs each former service's Flyway migrations against the SINGLE merged database (bikri_db).
 *
 * <p>Every module keeps its own migration files (classpath:db/migration/&lt;module&gt;) and its
 * own history table (flyway_schema_history_&lt;module&gt;) so existing version numbers (V1, V2, …)
 * never collide across modules and checksums from the pre-merge databases stay valid — the
 * merge-databases.sh script renames each old history table accordingly.
 *
 * <p>auth has no migrations (auth_db had no tables) and is intentionally absent.
 */
@Slf4j
@Configuration
public class MultiModuleFlywayConfig {

  private static final List<String> MODULES =
      List.of("user", "inventory", "sales", "billing", "printer");

  @Bean
  public List<Flyway> flywayMigrations(
      @Value("${spring.flyway.url}") String url,
      @Value("${spring.flyway.user}") String user,
      @Value("${spring.flyway.password}") String password) {

    String cleanUrl = url;
    if (url.startsWith("jdbc:mysql:") && !url.contains("createDatabaseIfNotExist=")) {
      cleanUrl += (url.contains("?") ? "&" : "?") + "createDatabaseIfNotExist=true";
    }
    final String dataSourceUrl = cleanUrl; // effectively-final copy for the lambda

    List<Flyway> instances =
        MODULES.stream()
            .map(
                module ->
                    Flyway.configure()
                        .dataSource(dataSourceUrl, user, password)
                        .locations("classpath:db/migration/" + module)
                        .table("flyway_schema_history_" + module)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load())
            .toList();

    // Migrate during context creation — before any traffic is served.
    for (int i = 0; i < instances.size(); i++) {
      log.info("Flyway migrate module={}", MODULES.get(i));
      instances.get(i).repair();
      instances.get(i).migrate();
    }
    return instances;
  }
}
