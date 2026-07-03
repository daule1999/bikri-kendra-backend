package com.vy.sales.user.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vy.sales.user.dto.RoleRequest;
import com.vy.sales.user.entity.Role;
import com.vy.sales.user.repository.RoleRepository;
import java.time.Duration;
import java.time.LocalDateTime;
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
public class RoleService {

  // ── Redis cache constants ─────────────────────────────────────────────────
  private static final String ROLES_CACHE_KEY = "roles:all";
  private static final Duration ROLES_TTL = Duration.ofHours(1);
  private static final ObjectMapper CACHE_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final RoleRepository roleRepository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  // =======================
  // CREATE ROLE
  // =======================
  public Mono<Role> create(RoleRequest request) {

    log.debug("ROLE_SERVICE_CREATE name={}", request.getName());

    return roleRepository
        .existsByName(request.getName())
        .flatMap(
            exists -> {
              if (exists) {
                log.warn("ROLE_ALREADY_EXISTS name={}", request.getName());
                return Mono.error(
                    new ResponseStatusException(HttpStatus.CONFLICT, "ROLE_ALREADY_EXISTS"));
              }

              Role role = new Role();
              role.setName(request.getName());
              role.setDescription(request.getDescription());
              role.setCreatedAt(LocalDateTime.now());

              return roleRepository
                  .save(role)
                  .flatMap(
                      saved ->
                          redisTemplate
                              .delete(ROLES_CACHE_KEY)
                              .onErrorResume(
                                  e -> {
                                    log.warn("Role cache delete failed on create", e);
                                    return Mono.empty();
                                  })
                              .thenReturn(saved))
                  .doOnSuccess(
                      saved ->
                          log.info("ROLE_SAVED id={} name={}", saved.getId(), saved.getName()));
            });
  }

  // =======================
  // GET ALL ROLES
  // =======================
  public Flux<Role> getAll() {

    log.debug("ROLE_SERVICE_GET_ALL");

    return redisTemplate
        .opsForValue()
        .get(ROLES_CACHE_KEY)
        .onErrorResume(
            e -> {
              log.warn("Role cache GET error, falling back to DB", e);
              return Mono.empty();
            })
        .flatMapMany(
            json -> {
              try {
                List<Role> cached =
                    CACHE_MAPPER.readValue(json, new TypeReference<List<Role>>() {});
                log.debug("Role cache HIT key={} count={}", ROLES_CACHE_KEY, cached.size());
                return Flux.fromIterable(cached);
              } catch (Exception e) {
                log.warn("Role cache deserialize error, falling back to DB", e);
                return Flux.empty();
              }
            })
        .switchIfEmpty(
            roleRepository
                .findAll()
                .collectList()
                .flatMapMany(
                    roles -> {
                      try {
                        String json = CACHE_MAPPER.writeValueAsString(roles);
                        return redisTemplate
                            .opsForValue()
                            .set(ROLES_CACHE_KEY, json, ROLES_TTL)
                            .onErrorResume(
                                e -> {
                                  log.warn("Role cache SET error", e);
                                  return Mono.empty();
                                })
                            .thenMany(Flux.fromIterable(roles));
                      } catch (Exception e) {
                        log.warn("Role cache serialize error", e);
                        return Flux.fromIterable(roles);
                      }
                    }))
        .doOnComplete(() -> log.info("ROLE_FETCH_ALL_COMPLETED"))
        .doOnError(ex -> log.error("ROLE_FETCH_ALL_FAILED reason={}", ex.getMessage(), ex));
  }

  // =======================
  // UPDATE ROLE
  // =======================
  public Mono<Role> update(Long id, RoleRequest request) {

    log.debug("ROLE_SERVICE_UPDATE_REQUEST id={} name={}", id, request.getName());

    return roleRepository
        .findById(id)
        .switchIfEmpty(
            Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND")))
        .flatMap(
            role -> {
              role.setName(request.getName());
              role.setDescription(request.getDescription());

              return roleRepository
                  .save(role)
                  .flatMap(
                      updated ->
                          redisTemplate
                              .delete(ROLES_CACHE_KEY)
                              .onErrorResume(
                                  e -> {
                                    log.warn("Role cache delete failed on update id={}", id, e);
                                    return Mono.empty();
                                  })
                              .thenReturn(updated))
                  .doOnSuccess(
                      updated ->
                          log.info(
                              "ROLE_UPDATED id={} name={}", updated.getId(), updated.getName()));
            })
        .doOnError(ex -> log.error("ROLE_UPDATE_FAILED id={} reason={}", id, ex.getMessage(), ex));
  }
}
