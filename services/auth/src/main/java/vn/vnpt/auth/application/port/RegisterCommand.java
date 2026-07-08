package vn.vnpt.auth.application.port;

public record RegisterCommand(String email, String password) {

  public RegisterCommand {
    if (email == null || email.isBlank() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
      throw new IllegalArgumentException("email must be a valid email address");
    }
    if (password == null || password.length() < 8) {
      throw new IllegalArgumentException("password must be at least 8 characters");
    }
  }
}