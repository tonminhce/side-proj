package vn.vnpt.auth.application.usecase;

import org.springframework.stereotype.Service;
import vn.vnpt.auth.application.port.ServiceTokenCommand;
import vn.vnpt.auth.application.port.ServiceTokenResult;
import vn.vnpt.auth.infrastructure.security.JwtIssuer;

@Service
public class IssueServiceTokenUseCase {

  private final JwtIssuer jwtIssuer;

  public IssueServiceTokenUseCase(JwtIssuer jwtIssuer) {
    this.jwtIssuer = jwtIssuer;
  }

  public ServiceTokenResult execute(ServiceTokenCommand cmd) {
    String token = jwtIssuer.issueServiceToken(
        cmd.serviceAccountId(), cmd.allowedRoles(), cmd.callerChain());
    return new ServiceTokenResult(token);
  }
}