package com.vy.sales.user.entity;

import lombok.*;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("role_permissions")
public class RolePermission {

  private Long roleId;
  private Long permissionId;
}
