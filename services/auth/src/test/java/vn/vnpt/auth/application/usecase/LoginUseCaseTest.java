package vn.vnpt.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.auth.application.port.AuthResult;
import vn.vnpt.auth.application.port.LoginCommand;
import vn.vnpt.auth.domain.Role;
import vn.vnpt.auth.infrastructure.entity.UserEntity;
import vn.vnpt.auth.infrastructure.repository.UserRepository;
import vn.vnpt.auth.infrastructure.security.JwtIssuer;
import vn.vnpt.auth.infrastructure.security.PasswordHasher;

class LoginUseCaseTest {

  private UserRepository repo;
  private PasswordHasher hasher;
  private JwtIssuer jwt;
  private LoginUseCase useCase;
  private Clock fixedClock;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(UserRepository.class);
    hasher = Mockito.mock(PasswordHasher.class);
    jwt = new JwtIssuer(new ObjectMapper());
    jwt.init();
    fixedClock = Clock.fixed(Instant.parse("2026-07-08T03:00:00Z"), ZoneOffset.UTC);
    useCase = new LoginUseCase(repo, hasher, jwt, fixedClock, new SimpleMeterRegistry());
    when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void execute_returnsSessionTokenOnCorrectPassword() {
    UserEntity user = UserEntity.builder()
        .id(1L).email("a@x.vn").passwordHash("hashed").role(Role.USER)
        .mfaEnrolled(false).failedAttempts(0).emailVerified(false)
        .createdAt(LocalDateTime.now()).build();
    when(repo.findByEmail("a@x.vn")).thenReturn(Optional.of(user));
    when(hasher.verify("password123", "hashed")).thenReturn(true);

    AuthResult r = useCase.execute(new LoginCommand("a@x.vn", "password123"));

    assertThat(r.userId()).isEqualTo(1L);
    assertThat(r.sessionToken()).isNotBlank();
  }

  @Test
  void execute_incrementsFailedAttemptsOnBadPassword() {
    UserEntity user = UserEntity.builder()
        .id(1L).email("a@x.vn").passwordHash("hashed").role(Role.USER)
        .mfaEnrolled(false).failedAttempts(0).emailVerified(false)
        .createdAt(LocalDateTime.now()).build();
    when(repo.findByEmail("a@x.vn")).thenReturn(Optional.of(user));
    when(hasher.verify("wrong", "hashed")).thenReturn(false);

    AuthResult r = useCase.execute(new LoginCommand("a@x.vn", "wrong"));

    assertThat(r.error()).isEqualTo("invalid_credentials");
    assertThat(user.getFailedAttempts()).isEqualTo(1);
  }

  @Test
  void execute_locksAccountAfter5FailedAttempts() {
    UserEntity user = UserEntity.builder()
        .id(1L).email("a@x.vn").passwordHash("hashed").role(Role.USER)
        .mfaEnrolled(false).failedAttempts(4).emailVerified(false)
        .createdAt(LocalDateTime.now()).build();
    when(repo.findByEmail("a@x.vn")).thenReturn(Optional.of(user));
    when(hasher.verify("wrong", "hashed")).thenReturn(false);

    AuthResult r = useCase.execute(new LoginCommand("a@x.vn", "wrong"));

    assertThat(r.error()).startsWith("account_locked");
    assertThat(user.getLockedUntil()).isNotNull();
  }

  @Test
  void execute_throwsAccountLockedWhenLockedUntilFuture() {
    UserEntity user = UserEntity.builder()
        .id(1L).email("a@x.vn").passwordHash("hashed").role(Role.USER)
        .mfaEnrolled(false).failedAttempts(0).emailVerified(false)
        .createdAt(LocalDateTime.now())
        .lockedUntil(LocalDateTime.of(2026, 7, 8, 5, 0, 0))  // 2h ahead
        .build();
    when(repo.findByEmail("a@x.vn")).thenReturn(Optional.of(user));

    AuthResult r = useCase.execute(new LoginCommand("a@x.vn", "password123"));

    assertThat(r.error()).isEqualTo("account_locked");
  }
}