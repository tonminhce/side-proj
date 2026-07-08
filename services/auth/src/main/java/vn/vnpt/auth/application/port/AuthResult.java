package vn.vnpt.auth.application.port;

import vn.vnpt.auth.domain.Role;

/** Auth result — Story 5.4. */
public record AuthResult(
    long userId,
    String email,
    Role role,
    boolean mfaRequired,
    String sessionToken,
    boolean captchaRequired,
    String error) {

  public static AuthResult error(String error) {
    return new AuthResult(0, null, null, false, null, false, error);
  }
}