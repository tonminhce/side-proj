package vn.vnpt.checkout.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.checkout.CheckoutApplication;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStateTransition;
import vn.vnpt.checkout.domain.OrderStatus;
import vn.vnpt.checkout.infrastructure.repository.OrderRepository;
import vn.vnpt.checkout.infrastructure.repository.OrderStateTransitionRepository;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;

/**
 * HTTP slice for {@link OrderController} — Story 2.5 / FR-22 (GET /api/orders/by-checkout/{uuid}).
 * Mirrors {@code CheckoutControllerTest} style: @SpringBootTest + Testcontainers + @MockitoBean.
 */
@SpringBootTest(classes = CheckoutApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class OrderControllerTest {

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

  @Autowired WebApplicationContext wac;
  @MockitoBean OrderRepository orderRepository;
  @MockitoBean OrderStateTransitionRepository transitionRepository;
  @MockitoBean ReserveInventoryUseCase reserveInventoryUseCase;

  private MockMvc mvc;

  @BeforeEach
  void setUpMvc() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
  }

  @Test
  void findByCheckout_returnsOrderWithTransitions() throws Exception {
    Order order = Order.builder()
        .tenantId("default")
        .cartUuid(10L)
        .checkoutUuid(20L)
        .paymentIntentId("pi_abc")
        .status(OrderStatus.PAYMENT_PENDING)
        .version(2L)
        .build();
    order.setUuid(100L);
    order.setCreatedAt(LocalDateTime.now());
    order.setUpdatedAt(LocalDateTime.now());
    OrderStateTransition tx = new OrderStateTransition(100L, null, OrderStatus.CREATED,
        "cart.submit", null);
    OrderStateTransition tx2 = new OrderStateTransition(100L, OrderStatus.CREATED,
        OrderStatus.STOCK_RESERVED, "stock.reserve", null);
    when(orderRepository.findByCheckoutUuid(20L)).thenReturn(java.util.Optional.of(order));
    when(transitionRepository.findByOrderUuidOrderByCreatedAtAsc(100L)).thenReturn(List.of(tx, tx2));

    mvc.perform(get("/api/orders/by-checkout/20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderUuid").value(100))
        .andExpect(jsonPath("$.checkoutUuid").value(20))
        .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.transitions", hasSize(2)))
        .andExpect(jsonPath("$.transitions[0].sagaStep").value("cart.submit"))
        .andExpect(jsonPath("$.transitions[1].toState").value("STOCK_RESERVED"));
  }

  @Test
  void findByCheckout_returns404WhenOrderMissing() throws Exception {
    when(orderRepository.findByCheckoutUuid(99L)).thenReturn(java.util.Optional.empty());

    mvc.perform(get("/api/orders/by-checkout/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.details.checkoutUuid").value(99));

    verify(transitionRepository, never()).findByOrderUuidOrderByCreatedAtAsc(ArgumentMatchers.any());
  }

  @Test
  void findByCheckout_returns400WhenUuidNonNumeric() throws Exception {
    mvc.perform(get("/api/orders/by-checkout/not-a-number"))
        .andExpect(status().isBadRequest());
  }
}