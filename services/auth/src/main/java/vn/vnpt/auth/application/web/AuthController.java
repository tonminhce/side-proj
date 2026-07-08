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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

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
    AuthResult result = registerUseCase.execute(cmd);
    if (result.error() != null) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", result.error()));
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "userId", result.userId(),
        "email", result.email(),
        "role", result.role().name(),
        "mfaRequired", result.mfaRequired(),
        "sessionToken", result.sessionToken(),
        "captchaRequired", result.captchaRequired()));
  }

  @PostMapping("/login")
  public ResponseEntity<Map<String, Object>> login(@RequestBody LoginCommand cmd) {
    AuthResult result = loginUseCase.execute(cmd);
    if ("invalid_credentials".equals(result.error())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", result.error()));
    }
    if (result.error() != null && result.error().startsWith("account_locked")) {
      return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
          "error", "account_locked",
          "userId", result.userId()));
    }
    return ResponseEntity.ok(Map.of(
        "userId", result.userId(),
        "email", result.email(),
        "role", result.role().name(),
        "mfaRequired", result.mfaRequired(),
        "sessionToken", result.sessionToken(),
        "captchaRequired", result.captchaRequired()));
  }

  /** Story 5.5 / FR-74 — service-account JWT. */
  @PostMapping("/service-token")
  public ResponseEntity<Map<String, Object>> serviceToken(@RequestBody ServiceTokenCommand cmd) {
    ServiceTokenResult result = issueServiceTokenUseCase.execute(cmd);
    return ResponseEntity.ok(Map.of(
        "sessionToken", result.sessionToken(),
        "serviceAccountId", cmd.serviceAccountId()));
  }
}