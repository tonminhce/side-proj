package vn.vnpt.customer.application.port;

/** Trust-boundary-validated command to create a Customer — Story 5.1. */
public record CreateCustomerCommand(long userId, String displayName, String email, String phone) {

  public CreateCustomerCommand {
    if (userId <= 0) {
      throw new IllegalArgumentException("userId must be positive (got " + userId + ")");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be null or blank");
    }
    if (email != null && !email.isBlank() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
      throw new IllegalArgumentException("email must be a valid email address (got " + email + ")");
    }
  }
}