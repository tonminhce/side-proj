package vn.vnpt.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.vnpt.auth.application.port.ServiceTokenCommand;
import vn.vnpt.auth.application.port.ServiceTokenResult;
import vn.vnpt.auth.infrastructure.security.JwtIssuer;

class IssueServiceTokenUseCaseTest {

  private IssueServiceTokenUseCase useCase;

  @BeforeEach
  void setUp() {
    JwtIssuer jwt = new JwtIssuer(new ObjectMapper(), "dev");
    jwt.init();
    useCase = new IssueServiceTokenUseCase(jwt);
  }

  @Test
  void execute_returnsTokenWithServiceAccountFields() {
    ServiceTokenResult r = useCase.execute(new ServiceTokenCommand(
        "checkout-svc", List.of("USER", "STAFF"), List.of("checkout", "payment")));
    assertThat(r.sessionToken()).isNotBlank();
    // Decode the payload (middle segment) and assert the service-account fields are present.
    String[] parts = r.sessionToken().split("\\.");
    String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
    assertThat(payloadJson).contains("\"serviceAccountId\":\"checkout-svc\"");
    assertThat(payloadJson).contains("\"callerChain\":[\"checkout\",\"payment\"]");
    assertThat(payloadJson).contains("\"allowedRoles\":[\"USER\",\"STAFF\"]");
  }

  @Test
  void execute_throwsOnNullServiceAccountId() {
    assertThatThrownBy(() -> new ServiceTokenCommand(null, List.of("USER"), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("serviceAccountId");
  }

  @Test
  void execute_throwsOnEmptyAllowedRoles() {
    assertThatThrownBy(() -> new ServiceTokenCommand("x", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowedRoles");
  }
}