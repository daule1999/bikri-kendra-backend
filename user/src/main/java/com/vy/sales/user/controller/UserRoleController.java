package com.vy.sales.user.controller;

import com.vy.sales.user.dto.UpdateUserRolesRequest;
import com.vy.sales.user.dto.UpdateUserRolesResponse;
import com.vy.sales.user.service.UserRoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users-svc/users-role")
@RequiredArgsConstructor
@Slf4j
public class UserRoleController {

  private final UserRoleService userRoleService;

  @PutMapping
  public Mono<UpdateUserRolesResponse> updateRoles(
      @RequestHeader(value = "X-Username", required = false) String username,
      @RequestHeader(value = "X-Roles", required = false) String rolesHeader,
      @RequestHeader(value = "X-Event-Id", required = false) String headerEventId,
      @Valid @RequestBody UpdateUserRolesRequest request) {

    if (request.getEventId() == null && headerEventId != null && !headerEventId.trim().isEmpty()) {
      try {
        request.setEventId(Long.parseLong(headerEventId.trim()));
      } catch (NumberFormatException e) {
        log.warn(
            "INVALID_EVENT_ID_HEADER in update roles: value={} error={}",
            headerEventId,
            e.getMessage());
      }
    }

    log.info(
        "USER_ROLE_UPDATE_REQUEST user={} roles={} targetUser={} eventId={} newRoles={}",
        username,
        rolesHeader,
        request.getUserId(),
        request.getEventId(),
        request.getRoleIds());

    if (request.getEventId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Event ID is required for role assignment");
    }

    // Basic validation: if client provided eventId it must be positive
    if (request.getEventId() != null && request.getEventId() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid eventId");
    }

    return userRoleService
        .updateUserRoles(request)
        .doOnSuccess(
            v ->
                log.info(
                    "USER_ROLE_UPDATE_SUCCESS user={} targetUser={} eventId={} newRoles={}",
                    username,
                    request.getUserId(),
                    request.getEventId(),
                    request.getRoleIds()))
        .doOnError(
            ex ->
                log.error(
                    "USER_ROLE_UPDATE_FAILED user={} targetUser={} eventId={} reason={}",
                    username,
                    request.getUserId(),
                    request.getEventId(),
                    ex.getMessage(),
                    ex));
  }
}
