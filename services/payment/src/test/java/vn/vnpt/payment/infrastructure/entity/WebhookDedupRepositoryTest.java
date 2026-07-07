package vn.vnpt.payment.infrastructure.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.payment.PaymentApplication;
import vn.vnpt.payment.application.port.WebhookDedupPort.AppendOutcome;
import vn.vnpt.payment.infrastructure.repository.WebhookDedupRepository;

/**
 * Integration test for {@link WebhookDedupRepository} — Story 3.2 / AC #5. Boots the full app
 * against a Testcontainers Postgres (Spring Boot 4 removed the {@code @DataJpaTest} slice; full
 * context is the supported path). Asserts the {@code INSERT ... ON CONFLICT DO NOTHING} native
 * query maps cleanly to {@code AppendOutcome.inserted=true|false}.
 */
@SpringBootTest(classes = PaymentApplication.class)
@ActiveProfiles("test")
@Testcontainers
@Transactional
@Rollback
class WebhookDedupRepositoryTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("payment_db")
          .withUsername("payment_user")
          .withPassword("payment_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired WebhookDedupRepository dedupRepo;

  @Test
  void append_insertsOnFirstCall() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

    AppendOutcome outcome = dedupRepo.append("evt_first_call", "payment_intent.succeeded", false, now);

    assertThat(outcome.inserted()).isTrue();
    assertThat(outcome.receivedAt()).isNotNull();
    assertThat(dedupRepo.existsByEventId("evt_first_call")).isTrue();
  }

  @Test
  void append_returnsInsertedFalseOnDuplicate() {
    LocalDateTime first = LocalDateTime.now(ZoneOffset.UTC);
    AppendOutcome firstOutcome = dedupRepo.append("evt_dup", "payment_intent.succeeded", false, first);
    assertThat(firstOutcome.inserted()).isTrue();

    LocalDateTime second = first.plusSeconds(1);
    AppendOutcome secondOutcome = dedupRepo.append("evt_dup", "charge.refunded", true, second);

    assertThat(secondOutcome.inserted()).isFalse();
    assertThat(dedupRepo.existsByEventId("evt_dup")).isTrue();
  }

  @Test
  void existsByEventId_returnsTrueOnDuplicate() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    assertThat(dedupRepo.existsByEventId("evt_unseen")).isFalse();
    dedupRepo.append("evt_unseen", "payment_intent.succeeded", false, now);
    assertThat(dedupRepo.existsByEventId("evt_unseen")).isTrue();
  }
}