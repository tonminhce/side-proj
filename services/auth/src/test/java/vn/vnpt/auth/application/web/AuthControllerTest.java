package vn.vnpt.auth.application.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.vnpt.auth.application.port.AuthResult;
import vn.vnpt.auth.application.port.ServiceTokenCommand;
import vn.vnpt.auth.application.port.ServiceTokenResult;
import vn.vnpt.auth.application.usecase.IssueServiceTokenUseCase;
import vn.vnpt.auth.application.usecase.LoginUseCase;
import vn.vnpt.auth.application.usecase.RegisterUseCase;
import vn.vnpt.auth.domain.Role;

class AuthControllerTest {

  private RegisterUseCase registerUseCase;
  private LoginUseCase loginUseCase;
  private IssueServiceTokenUseCase issueServiceTokenUseCase;
  private MockMvc mockMvc;
  private final ObjectMapper om = new ObjectMapper();

  @BeforeEach
  void setUp() {
    registerUseCase = Mockito.mock(RegisterUseCase.class);
    loginUseCase = Mockito.mock(LoginUseCase.class);
    issueServiceTokenUseCase = Mockito.mock(IssueServiceTokenUseCase.class);
    AuthController controller = new AuthController(registerUseCase, loginUseCase, issueServiceTokenUseCase);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void register_returns200_genericBody_eitherPath() throws Exception {
    // Duplicate email: use case returns "email_taken" but controller must NOT leak it.
    when(registerUseCase.execute(any())).thenReturn(AuthResult.error("email_taken"));
    mockMvc.perform(post("/api/auth/register")
            .contentType("application/json")
            .content(om.writeValueAsString(Map.of("email", "a@x.vn", "password", "password123"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("registration_submitted"))
        .andExpect(jsonPath("$.message").exists());

    // Successful create: same 200 + same body shape (no leak of userId/role/email).
    when(registerUseCase.execute(any())).thenReturn(new AuthResult(1L, "a@x.vn", Role.USER, false, "tok", false, null));
    mockMvc.perform(post("/api/auth/register")
            .contentType("application/json")
            .content(om.writeValueAsString(Map.of("email", "b@x.vn", "password", "password123"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("registration_submitted"))
        .andExpect(jsonPath("$.userId").doesNotExist())
        .andExpect(jsonPath("$.sessionToken").doesNotExist());
  }

  @Test
  void login_unknownEmail_returns401_sameBodyAsBadPassword() throws Exception {
    when(loginUseCase.execute(any())).thenReturn(AuthResult.error("invalid_credentials"));
    mockMvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content(om.writeValueAsString(Map.of("email", "nobody@x.vn", "password", "password123"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("invalid_credentials"))
        .andExpect(jsonPath("$.userId").doesNotExist());

    when(loginUseCase.execute(any())).thenReturn(AuthResult.error("invalid_credentials"));
    mockMvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content(om.writeValueAsString(Map.of("email", "real@x.vn", "password", "wrong"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("invalid_credentials"));
  }

  @Test
  void login_accountLocked_returns401_sameAsInvalidCredentials() throws Exception {
    // account_locked is also collapsed to invalid_credentials to avoid enumeration.
    when(loginUseCase.execute(any())).thenReturn(
        AuthResult.error("account_locked:lockedUntil=2026-07-09T12:00:00"));
    mockMvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content(om.writeValueAsString(Map.of("email", "a@x.vn", "password", "password123"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("invalid_credentials"));
  }

  @Test
  void login_success_returns200_fullPayload() throws Exception {
    when(loginUseCase.execute(any())).thenReturn(
        new AuthResult(7L, "u@x.vn", Role.USER, false, "jwt-tok", false, null));
    mockMvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content(om.writeValueAsString(Map.of("email", "u@x.vn", "password", "password123"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(7))
        .andExpect(jsonPath("$.sessionToken").value("jwt-tok"));
  }

  @Test
  void serviceToken_returnsToken() throws Exception {
    when(issueServiceTokenUseCase.execute(any())).thenReturn(new ServiceTokenResult("svc-tok"));
    mockMvc.perform(post("/api/auth/service-token")
            .contentType("application/json")
            .content(om.writeValueAsString(new ServiceTokenCommand(
                "checkout-svc", java.util.List.of("USER"), java.util.List.of()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionToken").value("svc-tok"))
        .andExpect(jsonPath("$.serviceAccountId").value("checkout-svc"));
  }
}