package com.vy.sales.inventory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class ApiResponse<T> {
  private boolean success;
  private T data;
  private String message;
  private String errorCode;

  /** Backward-compat constructor (3-arg) — sets errorCode to null */
  public ApiResponse(boolean success, T data, String message) {
    this.success = success;
    this.data = data;
    this.message = message;
    this.errorCode = null;
  }

  public static <T> ApiResponse<T> success(T data, String message) {
    return ApiResponse.<T>builder().success(true).message(message).data(data).build();
  }

  public static <T> ApiResponse<T> error(String message) {
    return ApiResponse.<T>builder().success(false).message(message).data(null).build();
  }

  public static <T> ApiResponse<T> error(String message, String errorCode) {
    return ApiResponse.<T>builder()
        .success(false)
        .message(message)
        .data(null)
        .errorCode(errorCode)
        .build();
  }
}
