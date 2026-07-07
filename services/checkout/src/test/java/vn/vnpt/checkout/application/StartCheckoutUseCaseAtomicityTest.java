package vn.vnpt.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

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
import vn.vnpt.checkout.application.port.StripePaymentGateway;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.exception.StripePaymentIntentException;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.checkout.infrastructure.outbox.CheckoutEventPublisher;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;

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
  @MockitoBean StripePaymentGateway stripePaymentGateway;
  @MockitoBean ReserveInventoryUseCase reserveInventoryUseCase;

  @org.junit.jupiter.api.BeforeEach
  void stubStripe() {
    Mockito
        .when(stripePaymentGateway.createPaymentIntent(anyLong(), anyString(), anyString()))
        .thenReturn(new StripePaymentGateway.Result("pi_atomic_abc", "pi_atomic_abc_secret"));
  }

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
            .cartLines(
                List.of(
                    CartLineSnapshot.builder()
                        .variantId(1001L)
                        .quantity(2)
                        .unitPriceMinor(50_000L)
                        .build()))
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

  /** Story 2.4 / FR-20 / AC #5 — Stripe failure must roll back the checkout row. */
  @Test
  void start_rollsBackCheckoutWhenStripeGatewayThrows() {
    Mockito
        .when(stripePaymentGateway.createPaymentIntent(anyLong(), anyString(), anyString()))
        .thenThrow(new StripePaymentIntentException("simulated Stripe failure"));

    StartCheckoutRequest request =
        StartCheckoutRequest.builder()
            .cartUuid(54321L)
            .userId("u-abc-456")
            .shippingAddress(
                ShippingAddress.builder()
                    .recipientName("Nguyen Van B")
                    .phone("0909876543")
                    .addressLine1("456 Tran Hung Dao")
                    .city("HN")
                    .province("HN")
                    .country("VN")
                    .build())
            .cartLines(
                List.of(
                    CartLineSnapshot.builder()
                        .variantId(2002L)
                        .quantity(1)
                        .unitPriceMinor(120_000L)
                        .build()))
            .build();

    assertThatThrownBy(() -> useCase.start(request))
        .isInstanceOf(StripePaymentIntentException.class);

    Integer checkoutRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM checkouts WHERE cart_uuid = 54321", Integer.class);
    assertThat(checkoutRows).isEqualTo(0);

    Integer outboxRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'checkout.started'", Integer.class);
    assertThat(outboxRows).isEqualTo(0);
  }

  /**
   * Story 2.5 / FR-22 — when the upstream (Stripe) fails BEFORE the checkout row is INSERTed,
   * the {@code checkout.started} event is never published and the saga never runs. The
   * storefront never sees a half-state — no checkout, no orders, no transition rows.
   */
  @Test
  void start_stripeThrows_rollsBackCheckoutAndSaga() {
    Mockito
        .when(stripePaymentGateway.createPaymentIntent(anyLong(), anyString(), anyString()))
        .thenThrow(new StripePaymentIntentException("simulated Stripe failure (Story 2.5 saga-rolls-back)"));

    StartCheckoutRequest request =
        StartCheckoutRequest.builder()
            .cartUuid(99001L)
            .userId("u-saga-1")
            .shippingAddress(
                ShippingAddress.builder()
                    .recipientName("Nguyen Van Saga")
                    .phone("0901112222")
                    .addressLine1("789 Saga Street")
                    .city("HCM")
                    .province("HCM")
                    .country("VN")
                    .build())
            .cartLines(
                List.of(
                    CartLineSnapshot.builder()
                        .variantId(3001L)
                        .quantity(1)
                        .unitPriceMinor(75_000L)
                        .build()))
            .build();

    assertThatThrownBy(() -> useCase.start(request))
        .isInstanceOf(StripePaymentIntentException.class);

    // No checkout row, no checkout.started outbox, no orders row, no transition rows.
    Integer checkoutRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM checkouts WHERE cart_uuid = 99001", Integer.class);
    assertThat(checkoutRows).isEqualTo(0);
    Integer outboxRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE event_type = 'checkout.started'", Integer.class);
    assertThat(outboxRows).isEqualTo(0);
    Integer orderRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM orders WHERE cart_uuid = 99001", Integer.class);
    assertThat(orderRows).isEqualTo(0);
    Integer transitionRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM order_state_transition", Integer.class);
    assertThat(transitionRows).isEqualTo(0);
  }
}