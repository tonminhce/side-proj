package vn.vnpt.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.checkout.CheckoutApplication;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.infrastructure.repository.OrderRepository;
import vn.vnpt.checkout.infrastructure.repository.OrderStateTransitionRepository;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;

/**
 * Append-only invariant test — Story 2.5 / FR-22 (NFR-OBS-3).
 *
 * <p>Verifies (a) the DB-level UNIQUE on {@code event_id} blocks duplicate inserts, and (b) the
 * application-level append-only contract: the repository exposes no {@code update} / {@code delete}
 * methods.
 */
@SpringBootTest(classes = CheckoutApplication.class)
@ActiveProfiles("test")
@Testcontainers
class OrderStateTransitionAppendOnlyTest {

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

  @Autowired OrderRepository orderRepository;
  @Autowired OrderStateTransitionRepository transitionRepository;
  @Autowired JdbcTemplate jdbc;
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  ReserveInventoryUseCase reserveInventoryUseCase;

  @Test
  void appendOnly_insertsAreAllowedButDuplicateEventIdRejected() {
    Order order = orderRepository.save(Order.builder()
        .tenantId("default")
        .cartUuid(7777L)
        .checkoutUuid(7777L)
        .status(OrderStatus.CREATED)
        .version(0L)
        .shippingAddress(ShippingAddress.builder()
            .recipientName("Nguyen Van A")
            .phone("0901234567")
            .addressLine1("123 Le Loi")
            .city("HCM")
            .province("HCM")
            .country("VN")
            .build())
        .build());

    OrderStateTransition first = transitionRepository.save(new OrderStateTransition(
        order.getUuid(), null, OrderStatus.CREATED, "cart.submit", null));

    // Second transition with a DIFFERENT saga step — should succeed (append, not update).
    OrderStateTransition second = transitionRepository.save(new OrderStateTransition(
        order.getUuid(), OrderStatus.CREATED, OrderStatus.STOCK_RESERVED, "stock.reserve", null));

    // Sanity: two rows for the order.
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM order_state_transition WHERE order_uuid = ?",
        Integer.class,
        order.getUuid());
    assertThat(count).isEqualTo(2);
    assertThat(first.getEventId()).isNotNull();
    assertThat(second.getEventId()).isNotNull();
    assertThat(first.getEventId()).isNotEqualTo(second.getEventId());

    // DB-level UNIQUE on event_id rejects the same id twice (manual SQL insert).
    assertThatThrownBy(() -> jdbc.update(
        "INSERT INTO order_state_transition (uuid, order_uuid, to_state, saga_step, event_id,"
            + " created_at, is_active, is_deleted) VALUES (?, ?, ?, ?, ?, now(), true, false)",
        999_999_999L, order.getUuid(), "CREATED", "dup", first.getEventId()))
        .hasMessageContaining("event_id");
  }
}