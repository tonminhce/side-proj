package vn.vnpt.auth.application.usecase;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.auth.application.port.AuthResult;
import vn.vnpt.auth.application.port.LoginCommand;
import vn.vnpt.auth.domain.Role;
import vn.vnpt.auth.infrastructure.entity.UserEntity;
import vn.vnpt.auth.infrastructure.repository.UserRepository;
import vn.vnpt.auth.infrastructure.security.JwtIssuer;
import vn.vnpt.util.security.PasswordHasher;

@Service
@Transactional
public class LoginUseCase {

  private static final int MAX_FAILED_ATTEMPTS = 5;
  private static final Duration LOCK_DURATION = Duration.ofHours(1);

  private final UserRepository userRepository;
  private final PasswordHasher passwordHasher;
  private final JwtIssuer jwtIssuer;
  private final Clock clock;
  private final Counter accountLockedCounter;

  public LoginUseCase(UserRepository userRepository,
                       PasswordHasher passwordHasher,
                       JwtIssuer jwtIssuer,
                       Clock clock,
                       MeterRegistry meterRegistry) {
    this.userRepository = userRepository;
    this.passwordHasher = passwordHasher;
    this.jwtIssuer = jwtIssuer;
    this.clock = clock;
    this.accountLockedCounter = Counter.builder("security.account.locked")
        .tag("service", "auth").register(meterRegistry);
  }

  public AuthResult execute(LoginCommand cmd) {
    var maybeUser = userRepository.findByEmail(cmd.email());
    if (maybeUser.isEmpty()) {
      return AuthResult.error("invalid_credentials");
    }
    UserEntity user = maybeUser.get();

    // Account lockout check.
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now(clock))) {
      return AuthResult.error("account_locked");
    }

    // Password verification.
    if (!passwordHasher.verify(cmd.password(), user.getPasswordHash())) {
      int failed = user.getFailedAttempts() + 1;
      user.setFailedAttempts(failed);
      if (failed >= MAX_FAILED_ATTEMPTS) {
        user.setLockedUntil(LocalDateTime.now(clock).plus(LOCK_DURATION));
        user.setFailedAttempts(0);
        accountLockedCounter.increment();
      }
      userRepository.save(user);
      if (user.getLockedUntil() != null) {
        return new AuthResult(0, null, null, false, null, false,
            "account_locked:lockedUntil=" + user.getLockedUntil());
      }
      return AuthResult.error("invalid_credentials");
    }

    // Success — reset failed attempts + update lastLoginAt.
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    user.setLastLoginAt(LocalDateTime.now(clock));
    userRepository.save(user);

    String token = jwtIssuer.issue(user.getId(), user.getEmail(), user.getRole().name());
    return new AuthResult(user.getId(), user.getEmail(), user.getRole(),
        user.isMfaEnrolled(), token, false, null);
  }
}