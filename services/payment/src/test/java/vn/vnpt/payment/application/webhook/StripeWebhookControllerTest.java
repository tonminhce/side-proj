package vn.vnpt.payment.application.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import vn.vnpt.payment.PaymentApplication;
import vn.vnpt.payment.application.port.StripeWebhookHandler.Outcome;
import vn.vnpt.payment.application.usecase.HandleStripeWebhookUseCase;

/**
 * HTTP slice for {@link StripeWebhookController} — Story 3.2 / AC #7. Spring Boot 4 removed the
 * {@code @WebMvcTest} slice; full {@code @SpringBootTest(MOCK)} + manual {@link MockMvc} via
 * {@link MockMvcBuilders#webAppContextSetup} is the supported path. {@code @MockitoBean} replaces
 * {@code @MockBean} (Spring 6.2+).
 */
@SpringBootTest(classes = PaymentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class StripeWebhookControllerTest {

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

  @Autowired WebApplicationContext webContext;
  @MockitoBean HandleStripeWebhookUseCase useCase;

  MockMvc mockMvc;

  @BeforeEach
  void setupMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
  }

  @Test
  void post_returns200_dedupFalse_onInsert() throws Exception {
    when(useCase.execute(any())).thenReturn(new Outcome(true, "evt_abc123"));

    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":"evt_abc123","type":"payment_intent.succeeded","livemode":false,"data":{},"created":1700000000}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received").value(true))
        .andExpect(jsonPath("$.dedup").value(false))
        .andExpect(jsonPath("$.eventId").value("evt_abc123"));
  }

  @Test
  void post_returns200_dedupTrue_onDuplicate() throws Exception {
    when(useCase.execute(any())).thenReturn(new Outcome(false, "evt_abc123"));

    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":"evt_abc123","type":"payment_intent.succeeded","livemode":false,"data":{},"created":1700000000}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dedup").value(true))
        .andExpect(jsonPath("$.eventId").value("evt_abc123"));
  }

  @Test
  void post_missingId_returns400() throws Exception {
    // Missing id triggers IllegalArgumentException at the StripeWebhookEvent trust boundary;
    // util/web/RestExceptionHandler translates it to 400 Bad Request.
    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"payment_intent.succeeded","livemode":false,"data":{},"created":1700000000}
                """))
        .andExpect(status().isBadRequest());
  }
}