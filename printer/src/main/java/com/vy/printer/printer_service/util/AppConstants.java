package com.vy.printer.printer_service.util;

public class AppConstants {
  private AppConstants() {}

  // Injected by Traefik forwardAuth (auth-middleware) — printer-service trusts these.
  public static final String X_USER_ID = "X-User-Id";
  public static final String X_USERNAME = "X-Username";
  public static final String X_ROLES = "X-Roles";

  // G3: event scope is OPTIONAL here (unlike sales-service where it is required=true).
  public static final String X_EVENT_ID = "X-Event-Id";

  public static final class JobStatus {
    private JobStatus() {}

    public static final String QUEUED = "QUEUED";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
  }
}
