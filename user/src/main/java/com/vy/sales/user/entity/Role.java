package com.vy.sales.user.entity;

import java.time.LocalDateTime;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("roles")
public class Role {

  @Id private Long id;

  private String name;
  private String description;
  private LocalDateTime createdAt;
}
