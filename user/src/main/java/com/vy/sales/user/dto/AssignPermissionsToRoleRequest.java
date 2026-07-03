package com.vy.sales.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssignPermissionsToRoleRequest {

  @NotNull private Long roleId;

  @NotEmpty private List<Long> permissionIds;
}
