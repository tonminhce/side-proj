package vn.vnpt.auth.application.port;

public record LoginCommand(String email, String password) {

  public LoginCommand {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (password == null || password.isBlank()) {
      throw new IllegalArgumentException("password must not be blank");
    }
  }
}