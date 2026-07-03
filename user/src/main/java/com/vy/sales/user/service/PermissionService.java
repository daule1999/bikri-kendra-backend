package com.vy.sales.user.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.user.dto.PermissionRequest;
import com.vy.sales.user.entity.Permission;
import com.vy.sales.user.repository.PermissionRepository;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final String PERMISSIONS_CACHE_KEY = "permissions:all";
  private static final Duration PERMISSIONS_TTL = Duration.ofHours(1);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final PermissionRepository permissionRepository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  public Mono<Permission> create(PermissionRequest request) {

    log.debug("PERMISSION_SERVICE_CREATE name={}", request.getName());

    return permissionRepository
        .existsByName(request.getName())
        .flatMap(
            exists -> {
              if (exists) {
                log.warn("PERMISSION_ALREADY_EXISTS name={}", request.getName());
                return Mono.error(
                    new ResponseStatusException(HttpStatus.CONFLICT, "PERMISSION_ALREADY_EXISTS"));
              }

              Permission p = new Permission();
              p.setName(request.getName());
              p.setDescription(request.getDescription());

              return permissionRepository
                  .save(p)
                  .flatMap(
                      saved ->
                          redisTemplate
                              .delete(PERMISSIONS_CACHE_KEY)
                              .onErrorResume(
                                  e -> {
                                    log.warn("Permissions cache delete failed on create", e);
                                    return Mono.empty();
                                  })
                              .thenReturn(saved))
                  .doOnSuccess(
                      saved ->
                          log.info(
                              "PERMISSION_SAVED id={} name={}", saved.getId(), saved.getName()));
            });
  }

  public Flux<Permission> getAll() {

    log.debug("PERMISSION_SERVICE_GET_ALL");

    return redisTemplate
        .opsForValue()
        .get(PERMISSIONS_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("Permissions cache GET error, falling back to DB", e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<Permission> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<Permission>>() {});
                log.debug("Permissions cache HIT count={}", cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                log.warn("Permissions cache deserialize error, falling back to DB", e);
                return Flux.<Permission>empty();
              }
            })
        .switchIfEmpty(
            permissionRepository
                .findAll()
                .collectList()
                .flatMapMany(
                    perms -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(perms);
                        return redisTemplate
                            .opsForValue()
                            .set(PERMISSIONS_CACHE_KEY, json, PERMISSIONS_TTL)
                            .onErrorResume(
                                e -> {
                                  log.warn("Permissions cache SET error", e);
                                  return Mono.empty();
                                })
                            .thenMany(Flux.fromIterable(perms));
                      } catch (Exception e) {
                        log.warn("Permissions cache serialize error", e);
                        return Flux.fromIterable(perms);
                      }
                    }))
        .doOnComplete(() -> log.info("PERMISSION_FETCH_ALL_COMPLETED"))
        .doOnError(ex -> log.error("PERMISSION_FETCH_ALL_FAILED reason={}", ex.getMessage(), ex));
  }

  public Mono<Permission> update(Long id, PermissionRequest request) {

    log.debug("PERMISSION_SERVICE_UPDATE_REQUEST id={} name={}", id, request.getName());

    return permissionRepository
        .findById(id)
        .switchIfEmpty(
            Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND")))
        .flatMap(
            p -> {
              p.setName(request.getName());
              p.setDescription(request.getDescription());

              return permissionRepository
                  .save(p)
                  .flatMap(
                      updated ->
                          redisTemplate
                              .delete(PERMISSIONS_CACHE_KEY)
                              .onErrorResume(
                                  e -> {
                                    log.warn(
                                        "Permissions cache delete failed on update id={}", id, e);
                                    return Mono.empty();
                                  })
                              .thenReturn(updated))
                  .doOnSuccess(
                      updated ->
                          log.info(
                              "PERMISSION_UPDATED id={} name={}",
                              updated.getId(),
                              updated.getName()));
            })
        .doOnError(
            ex -> log.error("PERMISSION_UPDATE_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }
}
