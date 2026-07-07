package vn.vnpt.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.checkout.CheckoutApplication;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.checkout.infrastructure.outbox.CheckoutEventPublisher;

/**
 * ADR-04 atomicity regression guard — when the outbox publisher throws AFTER the Checkout row
 * INSERT, the Checkout row MUST roll back. Mirrors {@code AdjustInventoryUseCaseAtomicityTest}
 * (Story 1.5) for the checkout path.
 *
 * <p>The existing {@code StartCheckoutUseCaseTest} verifies the happy path with a mocked publisher;
 * this test pins the rollback path so a regression that drops {@code @Transactional} or splits the
 * save + outbox calls would surface as a "ghost" Checkout row with no outbox event.
 */
@SpringBootTest(classes = CheckoutApplication.class)
@ActiveProfiles("test")
@Testcontainers
class StartCheckoutUseCaseAtomicityTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("checkout_db")
          .withUsername("checkout_user")
          .withPassword("checkout_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired StartCheckoutUseCase useCase;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean CheckoutEventPublisher publisher;

  @Test
  void start_rollsBackCheckoutWhenPublisherThrows() {
    Mockito
        .doThrow(new IllegalStateException("simulated publisher failure"))
        .when(publisher)
        .publishCheckoutStarted(ArgumentMatchers.any(), ArgumentMatchers.any());

    StartCheckoutRequest request =
        StartCheckoutRequest.builder()
            .cartUuid(12345L)
            .userId("u-abc-123")
            .shippingAddress(
                ShippingAddress.builder()
                    .recipientName("Nguyen Van A")
                    .phone("0901234567")
                    .addressLine1("123 Le Loi")
                    .city("HCM")
                    .province("HCM")
                    .country("VN")
                    .build())
            .cartLines(List.of(CartLineSnapshot.builder().variantId(1001L).quantity(2).build()))
            .build();

    assertThatThrownBy(() -> useCase.start(request)).isInstanceOf(IllegalStateException.class);

    Integer checkoutRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM checkouts WHERE cart_uuid = 12345", Integer.class);
    assertThat(checkoutRows).isEqualTo(0);

    Integer outboxRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'checkout.started'", Integer.class);
    assertThat(outboxRows).isEqualTo(0);
  }
}