package vn.vnpt.auth.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.auth.application.port.AuthResult;
import vn.vnpt.auth.application.port.RegisterCommand;
import vn.vnpt.auth.domain.Role;
import vn.vnpt.auth.infrastructure.entity.UserEntity;
import vn.vnpt.auth.infrastructure.repository.UserRepository;
import vn.vnpt.auth.infrastructure.security.JwtIssuer;
import vn.vnpt.util.security.PasswordHasher;

class RegisterUseCaseTest {

  private UserRepository repo;
  private PasswordHasher hasher;
  private JwtIssuer jwt;
  private RegisterUseCase useCase;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(UserRepository.class);
    hasher = Mockito.mock(PasswordHasher.class);
    jwt = new JwtIssuer(new ObjectMapper(), "dev");
    jwt.init();
    useCase = new RegisterUseCase(repo, hasher, jwt);
    when(repo.findByEmail("a@x.vn")).thenReturn(Optional.empty());
    when(repo.save(any())).thenAnswer(inv -> {
      UserEntity u = inv.getArgument(0);
      if (u.getId() == null) u.setId(1L);
      return u;
    });
    when(hasher.hash("password123")).thenReturn("hashed");
  }

  @Test
  void execute_persistsUserWithHashedPassword() {
    AuthResult r = useCase.execute(new RegisterCommand("a@x.vn", "password123"));
    assertThat(r.userId()).isEqualTo(1L);
    assertThat(r.email()).isEqualTo("a@x.vn");
    assertThat(r.role()).isEqualTo(Role.USER);
    assertThat(r.sessionToken()).isNotBlank();
  }

  @Test
  void execute_throwsOnNullEmail() {
    assertThatThrownBy(() -> new RegisterCommand(null, "password123"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void execute_throwsOnShortPassword() {
    assertThatThrownBy(() -> new RegisterCommand("a@x.vn", "short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("8 characters");
  }
}