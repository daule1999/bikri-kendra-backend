package com.vy.sales.user.service;

import com.vy.sales.user.dto.UpdateUserRolesRequest;
import com.vy.sales.user.dto.UpdateUserRolesResponse;
import com.vy.sales.user.entity.Role;
import com.vy.sales.user.entity.UserRole;
import com.vy.sales.user.repository.RoleRepository;
import com.vy.sales.user.repository.UserRepository;
import com.vy.sales.user.repository.UserRoleRepository;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserRoleService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;
  private final ReactiveRedisTemplate<String, String> redisTemplate;

  @Transactional
  public Mono<UpdateUserRolesResponse> updateUserRoles(UpdateUserRolesRequest request) {

    log.info(
        "UPDATE_USER_ROLES_REQUEST userId={} newRoles={}",
        request.getUserId(),
        request.getRoleIds());

    return userRepository
        .findById(request.getUserId())
        .switchIfEmpty(
            Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND")))
        .flatMap(
            user ->
                // delete previous roles for this user; if eventId provided, delete only for
                // that event
                (request.getEventId() != null
                        ? userRoleRepository.deleteByUserIdAndEventId(
                            user.getId(), request.getEventId())
                        : userRoleRepository.deleteByUserId(user.getId()))
                    .thenMany(assignNewRoles(user.getId(), request))
                    .map(UserRole::getRoleId)
                    .flatMap(roleRepository::findById)
                    .map(Role::getName)
                    .collect(Collectors.toSet())
                    .map(
                        roles -> {
                          UpdateUserRolesResponse response = new UpdateUserRolesResponse();
                          response.setUserId(user.getId());
                          response.setUsername(user.getUsername());
                          response.setRoles(roles);
                          response.setStatus(user.getStatus().name());
                          response.setUpdatedAt(LocalDateTime.now());
                          return response;
                        })
                    .flatMap(
                        res ->
                            // Invalidate auth cache so next auth lookup is fresh
                            redisTemplate
                                .delete(
                                    UserAuthorizationService.AUTH_CACHE_PREFIX + user.getUsername())
                                .onErrorResume(
                                    e -> {
                                      log.warn(
                                          "Auth cache delete failed on updateUserRoles userId={}",
                                          user.getId(),
                                          e);
                                      return Mono.empty();
                                    })
                                .thenReturn(res))
                    .doOnSuccess(
                        res ->
                            log.info(
                                "UPDATE_USER_ROLES_SUCCESS userId={} roles={}",
                                res.getUserId(),
                                res.getRoles()))
                    .doOnError(
                        ex ->
                            log.error(
                                "UPDATE_USER_ROLES_FAILED userId={} reason={}",
                                request.getUserId(),
                                ex.getMessage(),
                                ex)));
  }

  private Flux<UserRole> assignNewRoles(Long userId, UpdateUserRolesRequest request) {
    return Flux.fromIterable(request.getRoleIds())
        .distinct()
        .flatMap(
            roleId ->
                roleRepository
                    .findById(roleId)
                    .switchIfEmpty(
                        Mono.error(
                            new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Role not found: " + roleId)))
                    .flatMap(
                        role -> {
                          UserRole ur = new UserRole();
                          ur.setUserId(userId);
                          ur.setRoleId(role.getId());
                          ur.setEventId(request.getEventId());
                          ur.setIsActive(
                              request.getIsActive() == null ? true : request.getIsActive());
                          ur.setAssignedAt(LocalDateTime.now());
                          return userRoleRepository.save(ur);
                        }));
  }
}
