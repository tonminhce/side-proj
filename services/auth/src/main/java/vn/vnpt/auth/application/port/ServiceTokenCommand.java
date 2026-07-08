package vn.vnpt.auth.application.port;

import java.util.List;

/** Trust-boundary-validated command for a service-account token — Story 5.5 / FR-74. */
public record ServiceTokenCommand(
    String serviceAccountId,
    List<String> allowedRoles,
    List<String> callerChain) {

  public ServiceTokenCommand {
    if (serviceAccountId == null || serviceAccountId.isBlank()) {
      throw new IllegalArgumentException("serviceAccountId must not be null or blank");
    }
    if (allowedRoles == null || allowedRoles.isEmpty()) {
      throw new IllegalArgumentException("allowedRoles must not be null or empty");
    }
    // callerChain may be null/empty — represents a top-level service call.
  }
}