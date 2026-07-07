package vn.vnpt.checkout.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import vn.vnpt.checkout.application.GetCheckoutUseCase;
import vn.vnpt.checkout.application.StartCheckoutUseCase;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.exception.CheckoutNotFoundException;

/** Story 2.3 / FR-19, FR-21 — HTTP slice. Boot 4 uses {@code @MockitoBean} for bean overrides. */
@SpringBootTest(
    classes = CheckoutApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class CheckoutControllerTest {

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
  @MockitoBean StartCheckoutUseCase startCheckoutUseCase;
  @MockitoBean GetCheckoutUseCase getCheckoutUseCase;
  @MockitoBean ReserveInventoryUseCase reserveInventoryUseCase;

  private MockMvc mvc;

  private Checkout checkout(long uuid, CheckoutStatus status) {
    Checkout c =
        Checkout.builder()
            .tenantId("default")
            .cartUuid(12345L)
            .userId("u-abc-123")
            .status(status)
            .version(0L)
            .stripeClientSecret("pi_xxx_secret_xxx")
            .paymentIntentId("pi_xxx")
            .shippingAddress(
                ShippingAddress.builder()
                    .recipientName("Nguyen Van A")
                    .phone("0901234567")
                    .addressLine1("123 Le Loi")
                    .city("HCM")
                    .province("HCM")
                    .country("VN")
                    .build())
            .build();
    c.setUuid(uuid);
    return c;
  }

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
  }

  @Test
  void postStart_validRequest_returns201WithCheckoutIdAndPaymentPendingStatus() throws Exception {
    when(startCheckoutUseCase.start(any())).thenReturn(checkout(12345L, CheckoutStatus.PAYMENT_PENDING));

    String body =
        "{\"cartUuid\":12345,\"userId\":\"u-abc-123\",\"shippingAddress\":{"
            + "\"recipientName\":\"Nguyen Van A\",\"phone\":\"0901234567\","
            + "\"addressLine1\":\"123 Le Loi\",\"city\":\"HCM\",\"province\":\"HCM\",\"country\":\"VN\"},"
            + "\"cartLines\":[{\"variantId\":1001,\"quantity\":2,\"unitPriceMinor\":50000}]}";

    mvc.perform(post("/api/checkouts/start").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.checkoutId").value(12345))
        .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
        .andExpect(jsonPath("$.cartUuid").value(12345))
        // Story 2.4 / FR-20 / AC #3 — BFF forwards paymentIntentId + clientSecret to the
        // storefront for the Stripe Elements handoff.
        .andExpect(jsonPath("$.paymentIntentId").value("pi_xxx"))
        .andExpect(jsonPath("$.stripeClientSecret").value("pi_xxx_secret_xxx"));
  }

  @Test
  void postStart_invalidRequest_returns400() throws Exception {
    // Bean Validation fires before the use case — missing cartUuid triggers @NotNull.
    mvc.perform(
            post("/api/checkouts/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u-abc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
  }

  @Test
  void getByUuid_existingCheckout_returns200() throws Exception {
    Checkout co = checkout(12345L, CheckoutStatus.PAYMENT_PENDING);
    co.setCreatedAt(java.time.LocalDateTime.now());
    when(getCheckoutUseCase.findByCheckoutUuid(12345L)).thenReturn(co);

    mvc.perform(get("/api/checkouts/12345"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checkoutId").value(12345))
        .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.shippingAddress.city").value("HCM"));
  }

  @Test
  void getByUuid_unknownUuid_returns404() throws Exception {
    doThrow(new CheckoutNotFoundException(99999L))
        .when(getCheckoutUseCase)
        .findByCheckoutUuid(99999L);

    mvc.perform(get("/api/checkouts/99999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.status").value("NOT_FOUND"))
        .andExpect(jsonPath("$.details.checkoutUuid").value(99999));
  }
}