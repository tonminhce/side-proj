package vn.vnpt.auth.application.usecase;

import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.auth.application.port.AuthResult;
import vn.vnpt.auth.application.port.RegisterCommand;
import vn.vnpt.auth.domain.Role;
import vn.vnpt.auth.infrastructure.entity.UserEntity;
import vn.vnpt.auth.infrastructure.repository.UserRepository;
import vn.vnpt.auth.infrastructure.security.JwtIssuer;
import vn.vnpt.auth.infrastructure.security.PasswordHasher;

@Service
@Transactional
public class RegisterUseCase {

  private final UserRepository userRepository;
  private final PasswordHasher passwordHasher;
  private final JwtIssuer jwtIssuer;

  public RegisterUseCase(UserRepository userRepository,
                          PasswordHasher passwordHasher,
                          JwtIssuer jwtIssuer) {
    this.userRepository = userRepository;
    this.passwordHasher = passwordHasher;
    this.jwtIssuer = jwtIssuer;
  }

  public AuthResult execute(RegisterCommand cmd) {
    if (userRepository.findByEmail(cmd.email()).isPresent()) {
      return AuthResult.error("email_taken");
    }
    UserEntity user = UserEntity.builder()
        .email(cmd.email())
        .passwordHash(passwordHasher.hash(cmd.password()))
        .role(Role.USER)
        .mfaEnrolled(false)
        .failedAttempts(0)
        .emailVerified(false)
        .createdAt(LocalDateTime.now())
        .build();
    UserEntity saved = userRepository.save(user);
    String token = jwtIssuer.issue(saved.getId(), saved.getEmail(), saved.getRole().name());
    return new AuthResult(saved.getId(), saved.getEmail(), saved.getRole(),
        false, token, false, null);
  }
}