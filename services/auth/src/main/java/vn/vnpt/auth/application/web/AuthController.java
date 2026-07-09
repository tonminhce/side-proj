package vn.vnpt.auth.application.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.auth.application.port.AuthResult;
import vn.vnpt.auth.application.port.LoginCommand;
import vn.vnpt.auth.application.port.RegisterCommand;
import vn.vnpt.auth.application.port.ServiceTokenCommand;
import vn.vnpt.auth.application.port.ServiceTokenResult;
import vn.vnpt.auth.application.usecase.IssueServiceTokenUseCase;
import vn.vnpt.auth.application.usecase.LoginUseCase;
import vn.vnpt.auth.application.usecase.RegisterUseCase;

/** Auth endpoints — Epic 5 follow-up / FR-73, FR-74, FR-75, FR-76.
 *  Email-enumeration hardening: register + login return identical status + body
 *  for the "no such user / wrong password / duplicate email" cases so a caller
 *  cannot probe which emails are registered. The detail error codes are still
 *  emitted internally to AuthResult for ops/audit logging but never leak in
 *  the HTTP response. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final String GENERIC_INVALID = "invalid_credentials";
  private static final String GENERIC_OK_REGISTER = "registration_submitted";
  private static final Map<String, Object> INVALID_BODY = Map.of("error", GENERIC_INVALID);

  private final RegisterUseCase registerUseCase;
  private final LoginUseCase loginUseCase;
  private final IssueServiceTokenUseCase issueServiceTokenUseCase;

  public AuthController(RegisterUseCase registerUseCase, LoginUseCase loginUseCase,
                        IssueServiceTokenUseCase issueServiceTokenUseCase) {
    this.registerUseCase = registerUseCase;
    this.loginUseCase = loginUseCase;
    this.issueServiceTokenUseCase = issueServiceTokenUseCase;
  }

  @PostMapping("/register")
  public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterCommand cmd) {
    // Always returns 200 with a generic body. The use case still creates the user if
    // the email is free; if it's a duplicate, no user row is created and no
    // distinguishing signal leaks. The real password-verification flow happens at login.
    registerUseCase.execute(cmd);
    return ResponseEntity.ok(Map.of(
        "status", GENERIC_OK_REGISTER,
        "message", "If this email is new, a verification link has been sent."));
  }

  @PostMapping("/login")
  public ResponseEntity<Map<String, Object>> login(@RequestBody LoginCommand cmd) {
    AuthResult result = loginUseCase.execute(cmd);
    if (result.error() != null) {
      // All failure modes — invalid_credentials, account_locked, or any future
      // error code — collapse to the same 401 + identical body. Login-account
      // lockout is reported via Micrometer counter + ops log instead.
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_BODY);
    }
    return ResponseEntity.ok(Map.of(
        "userId", result.userId(),
        "email", result.email(),
        "role", result.role().name(),
        "mfaRequired", result.mfaRequired(),
        "sessionToken", result.sessionToken(),
        "captchaRequired", result.captchaRequired()));
  }

  /** Story 5.5 / FR-74 — service-account JWT. Gated upstream by InternalTokenAuthFilter
   *  (X-Internal-Token header) — see AuthSecurityConfig. */
  @PostMapping("/service-token")
  public ResponseEntity<Map<String, Object>> serviceToken(@RequestBody ServiceTokenCommand cmd) {
    ServiceTokenResult result = issueServiceTokenUseCase.execute(cmd);
    return ResponseEntity.ok(Map.of(
        "sessionToken", result.sessionToken(),
        "serviceAccountId", cmd.serviceAccountId()));
  }
}