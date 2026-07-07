package vn.vnpt.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.cart.application.CartAutoExpireSweeperJob;
import vn.vnpt.cart.application.GetOrCreateCartUseCase;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

/**
 * End-to-end outbox tests for Story 2.2 (FR-17 + FR-18). Unlike {@code CartControllerTest} (which
 * mocks the use cases), this boots the FULL wiring — real use cases, real {@code CartEventPublisher},
 * real {@code ModulithOutboxPublisher} — against a Testcontainers Postgres, and asserts the event row
 * actually lands in the {@code outbox} table with the correct type/payload/signature. This is the
 * automated equivalent of {@code dev/scripts/cart_expiry_smoke.sh}, the only story flow that was
 * previously verified only by Mockito or by a manual shell script.
 */
@SpringBootTest(classes = CartApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class CartEventOutboxE2ETest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("cart_db")
          .withUsername("cart_user")
          .withPassword("cart_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired WebApplicationContext wac;
  @Autowired GetOrCreateCartUseCase getOrCreateCartUseCase;
  @Autowired CartAutoExpireSweeperJob sweeperJob;
  @Autowired CartRepository cartRepository;
  @Autowired JdbcTemplate jdbc;

  private MockMvc mvc() {
    return MockMvcBuilders.webAppContextSetup(wac).build();
  }

  /**
   * FR-17 — POST a line through the real HTTP path and confirm a {@code cart.line.added} row lands in
   * the outbox with the variant/quantity payload and a non-empty HMAC signature.
   */
  @Test
  void addLine_overHttp_emitsCartLineAddedRowInOutbox() throws Exception {
    Cart cart = getOrCreateCartUseCase.getOrCreate("g-e2e-lineadded", null);
    Long uuid = cart.getUuid();

    mvc()
        .perform(
            post("/api/carts/{uuid}/lines", uuid)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":1001,\"quantity\":2,\"expectedCartVersion\":0}"))
        .andExpect(status().isCreated());

    Integer rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'cart.line.added' AND aggregate_id = ?",
            Integer.class,
            uuid);
    assertThat(rows).isEqualTo(1);

    String variantId =
        jdbc.queryForObject(
            "SELECT payload->>'variantId' FROM outbox"
                + " WHERE event_type = 'cart.line.added' AND aggregate_id = ?",
            String.class,
            uuid);
    String quantity =
        jdbc.queryForObject(
            "SELECT payload->>'quantity' FROM outbox"
                + " WHERE event_type = 'cart.line.added' AND aggregate_id = ?",
            String.class,
            uuid);
    String hmac =
        jdbc.queryForObject(
            "SELECT signatures->>'hmac_sha256' FROM outbox"
                + " WHERE event_type = 'cart.line.added' AND aggregate_id = ?",
            String.class,
            uuid);

    assertThat(variantId).isEqualTo("1001");
    assertThat(quantity).isEqualTo("2");
    assertThat(hmac).isNotBlank();
  }

  /**
   * FR-18 — a cart past its TTL is transitioned to {@code ABANDONED} by the real sweeper and a
   * {@code cart.expired} row lands in the outbox carrying {@code previousStatus = ANONYMOUS}. The
   * scheduled trigger is invoked directly (no {@code Thread.sleep}) for a deterministic test.
   */
  @Test
  void sweeper_expiresPastTtlCart_emitsCartExpiredRowInOutbox() {
    Cart cart = getOrCreateCartUseCase.getOrCreate("g-e2e-expired", null);
    Long uuid = cart.getUuid();

    // Force the TTL into the past so the sweeper query picks it up.
    jdbc.update("UPDATE carts SET expires_at = now() - INTERVAL '1 day' WHERE uuid = ?", uuid);

    sweeperJob.sweep();

    Cart reloaded = cartRepository.findById(uuid).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(CartStatus.ABANDONED);
    assertThat(reloaded.getVersion()).isGreaterThan(cart.getVersion());

    Integer rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'cart.expired' AND aggregate_id = ?",
            Integer.class,
            uuid);
    assertThat(rows).isEqualTo(1);

    String previousStatus =
        jdbc.queryForObject(
            "SELECT payload->>'previousStatus' FROM outbox"
                + " WHERE event_type = 'cart.expired' AND aggregate_id = ?",
            String.class,
            uuid);
    String hmac =
        jdbc.queryForObject(
            "SELECT signatures->>'hmac_sha256' FROM outbox"
                + " WHERE event_type = 'cart.expired' AND aggregate_id = ?",
            String.class,
            uuid);

    assertThat(previousStatus).isEqualTo("ANONYMOUS");
    assertThat(hmac).isNotBlank();
  }
}
