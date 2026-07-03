package com.vy.sales.user.bootstrap;

import com.vy.sales.user.entity.*;
import com.vy.sales.user.repository.*;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class BootstrapApplication {

  private final RoleRepository roleRepo;
  private final PermissionRepository permissionRepo;
  private final RolePermissionRepository rolePermissionRepo;
  private final UserRepository userRepo;
  private final UserRoleRepository userRoleRepo;
  private final PasswordEncoder passwordEncoder;

  @EventListener(ApplicationReadyEvent.class)
  public void bootstrapAll() {
    bootstrapPermissions();
    bootstrapRoles();
    bootstrapRolesAndPermissions();
    createAdminIfNotExists();
  }

  public Mono<Void> bootstrapRoles() {
    return Flux.fromArray(AppRole.values())
        .concatMap(this::createIfNotExists)
        .then()
        .doOnSuccess(v -> log.info("Roles bootstrap completed"))
        .doOnError(e -> log.error("Roles bootstrap failed", e));
  }

  private Mono<Role> createIfNotExists(AppRole role) {

    return roleRepo
        .existsByName(role.name())
        .flatMap(
            exists -> {
              if (exists) {
                return Mono.empty();
              }

              Role entity =
                  Role.builder()
                      .name(role.name())
                      .description(role.name().replace("_", " ").toLowerCase())
                      .createdAt(LocalDateTime.now())
                      .build();

              return roleRepo
                  .save(entity)
                  .doOnSuccess(r -> log.info("Role created: {}", r.getName()));
            });
  }

  public Mono<Void> bootstrapPermissions() {
    return Flux.fromArray(AppPermission.values())
        .concatMap(this::createIfNotExists)
        .then() // Mono<Void>
        .doOnSuccess(v -> log.info("Permissions bootstrap completed"))
        .doOnError(e -> log.error("Permission bootstrap failed", e));
  }

  private Mono<Permission> createIfNotExists(AppPermission permission) {

    return permissionRepo
        .existsByName(permission.name())
        .flatMap(
            exists -> {
              if (exists) {
                return Mono.empty();
              }

              Permission entity =
                  Permission.builder()
                      .name(permission.name())
                      .description(permission.name().replace("_", " ").toLowerCase())
                      .build();

              return permissionRepo
                  .save(entity)
                  .doOnSuccess(p -> log.info("Permission created: {}", p.getName()));
            });
  }

  public Mono<Void> bootstrapRolesAndPermissions() {

    Map<AppRole, List<AppPermission>> rolePermissionMap =
        Map.of(
            AppRole.ADMIN,
            List.of(AppPermission.values()),
            AppRole.INVENTORY_MANAGER,
            List.of(
                AppPermission.ITEM_CREATE,
                AppPermission.ITEM_UPDATE,
                AppPermission.ITEM_VIEW,
                AppPermission.STOCK_VIEW,
                AppPermission.STOCK_ADD,
                AppPermission.STOCK_ADJUST,
                AppPermission.STOCK_TRANSFER),
            AppRole.SHOP_SUPERVISOR,
            List.of(
                AppPermission.BILL_CANCEL,
                AppPermission.BILL_DISCOUNT_APPLY,
                AppPermission.BILL_CREATE,
                AppPermission.BILL_VIEW,
                AppPermission.PAYMENT_COLLECT),
            AppRole.CASHIER,
            List.of(
                AppPermission.BILL_CREATE, AppPermission.BILL_VIEW, AppPermission.PAYMENT_COLLECT),
            AppRole.ACCOUNTS,
            List.of(
                AppPermission.REPORT_DAILY_VIEW,
                AppPermission.REPORT_EVENT_VIEW,
                AppPermission.PROFIT_MARGIN_VIEW));

    return Flux.fromIterable(rolePermissionMap.keySet())
        .concatMap(this::createRoleIfNotExists)
        .thenMany(
            Flux.fromIterable(rolePermissionMap.entrySet())
                .concatMap(entry -> assignPermissionsToRole(entry.getKey(), entry.getValue())))
        .then()
        .doOnSuccess(v -> log.info("Roles & permissions bootstrapped"))
        .doOnError(e -> log.error("Roles & permissions bootstrap failed", e));
  }

  private Mono<Role> createRoleIfNotExists(AppRole role) {
    return roleRepo
        .findByName(role.name())
        .switchIfEmpty(
            roleRepo
                .save(
                    Role.builder()
                        .name(role.name())
                        .description(role.name().replace("_", " ").toLowerCase())
                        .createdAt(LocalDateTime.now())
                        .build())
                .onErrorResume(
                    e -> {
                      if (e instanceof R2dbcDataIntegrityViolationException) {
                        log.warn("Role {} already exists, skipping", role.name());
                        return roleRepo.findByName(role.name());
                      }
                      return Mono.error(e);
                    }));
  }

  private Mono<Void> assignPermissionsToRole(AppRole role, List<AppPermission> permissions) {

    return roleRepo
        .findByName(role.name())
        .flatMapMany(
            dbRole ->
                Flux.fromIterable(permissions)
                    .flatMap(
                        permission ->
                            permissionRepo
                                .findByName(permission.name())
                                .flatMap(
                                    dbPermission ->
                                        rolePermissionRepo
                                            .existsByRoleIdAndPermissionId(
                                                dbRole.getId(), dbPermission.getId())
                                            .flatMap(
                                                exists ->
                                                    exists
                                                        ? Mono.empty() // skip
                                                        // if
                                                        // mapping
                                                        // exists
                                                        : rolePermissionRepo.save(
                                                            new RolePermission(
                                                                dbRole.getId(),
                                                                dbPermission.getId()))))))
        .then();
  }

  public Mono<Void> createAdminIfNotExists() {
    return userRepo
        .existsByUsername("admin")
        .flatMap(
            exists -> {
              if (exists) {
                log.info("Admin user already exists, skipping bootstrap");
                return Mono.empty();
              }
              log.info("Creating default ADMIN user");
              User admin =
                  User.builder()
                      .username("admin")
                      .email("admin@system.com")
                      .mobile("8709254347")
                      .fullName("System Administrator")
                      .passwordHash(passwordEncoder.encode("Admin@123"))
                      .status(UserStatus.ACTIVE)
                      .build();

              return userRepo.save(admin);
            })
        .doOnSuccess(v -> log.info("Admin user created"))
        .doOnError(e -> log.error("Failed to create admin user", e))
        .then();
  }
}
