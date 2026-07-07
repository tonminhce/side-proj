package vn.vnpt.cart.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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
import vn.vnpt.cart.CartApplication;
import vn.vnpt.cart.application.AddLineUseCase;
import vn.vnpt.cart.application.GetOrCreateCartUseCase;
import vn.vnpt.cart.application.MergeCartUseCase;
import vn.vnpt.cart.application.MergeResult;
import vn.vnpt.cart.application.RemoveLineUseCase;
import vn.vnpt.cart.application.UpdateLineQuantityUseCase;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.exception.AnonymousCartOwnershipConflictException;
import vn.vnpt.cart.domain.exception.CartLineNotFoundException;
import vn.vnpt.cart.domain.exception.CartNotFoundException;
import vn.vnpt.cart.domain.exception.CartVersionConflictException;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;

/**
 * Slice test for {@link CartController} — Story 2.1 HTTP layer. Boot 4 uses
 * {@code @SpringBootTest(webEnvironment=MOCK)} + a manually-built {@link MockMvc}; {@code @MockBean}
 * is replaced by {@code @MockitoBean}.
 */
@SpringBootTest(classes = CartApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class CartControllerTest {

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

  @MockitoBean GetOrCreateCartUseCase getOrCreateCartUseCase;
  @MockitoBean AddLineUseCase addLineUseCase;
  @MockitoBean UpdateLineQuantityUseCase updateLineQuantityUseCase;
  @MockitoBean RemoveLineUseCase removeLineUseCase;
  @MockitoBean MergeCartUseCase mergeCartUseCase;
  @MockitoBean CartLineRepository cartLineRepository;

  private MockMvc mvc;

  private Cart cart(long uuid, long version, CartStatus status) {
    Cart c = Cart.builder().tenantId("default").userId("u-1").status(status).build();
    c.setUuid(uuid);
    c.setVersion(version);
    c.setCreatedAt(LocalDateTime.now());
    return c;
  }

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    when(cartLineRepository.findByCartUuid(any())).thenReturn(List.of());
  }

  @Test
  void getCart_returns200WithCartJson() throws Exception {
    when(getOrCreateCartUseCase.findByUuid(200L)).thenReturn(cart(200L, 0L, CartStatus.ACTIVE));

    mvc.perform(get("/api/carts/200"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cartUuid").value(200))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.subtotalCents").value(0))
        .andExpect(jsonPath("$.currency").value("VND"));
  }

  @Test
  void addLine_returns201_andBodyHasIncrementedVersion() throws Exception {
    when(addLineUseCase.addLine(eq(200L), eq(1001L), eq(2), any()))
        .thenReturn(cart(200L, 1L, CartStatus.ACTIVE));

    mvc.perform(
            post("/api/carts/200/lines")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":1001,\"quantity\":2,\"expectedCartVersion\":0}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void merge_returns200OnFirstCall() throws Exception {
    when(mergeCartUseCase.merge("g-1", "u-1"))
        .thenReturn(new MergeResult(cart(200L, 0L, CartStatus.ACTIVE), false));

    mvc.perform(
            post("/api/carts/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestCartId\":\"g-1\",\"userId\":\"u-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cartUuid").value(200))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void merge_returns200OnRetry() throws Exception {
    when(mergeCartUseCase.merge("g-1", "u-1"))
        .thenReturn(new MergeResult(cart(200L, 0L, CartStatus.ACTIVE), true));

    mvc.perform(
            post("/api/carts/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestCartId\":\"g-1\",\"userId\":\"u-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cartUuid").value(200));
  }

  @Test
  void merge_returns409OnDifferentUser() throws Exception {
    doThrow(new AnonymousCartOwnershipConflictException("g-1", "user-A"))
        .when(mergeCartUseCase)
        .merge("g-1", "user-B");

    mvc.perform(
            post("/api/carts/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestCartId\":\"g-1\",\"userId\":\"user-B\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.details.ownerUserId").value("user-A"));
  }

  // ---- Gap-fill: error mapping + remaining endpoints ----

  @Test
  void createCart_returns201_andBodyShape() throws Exception {
    when(getOrCreateCartUseCase.getOrCreate("g-new", null))
        .thenReturn(cart(201L, 0L, CartStatus.ANONYMOUS));

    mvc.perform(
            post("/api/carts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestCartId\":\"g-new\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.cartUuid").value(201))
        .andExpect(jsonPath("$.status").value("ANONYMOUS"));
  }

  @Test
  void getCart_returns404_whenCartMissing() throws Exception {
    doThrow(new CartNotFoundException(404L)).when(getOrCreateCartUseCase).findByUuid(404L);

    mvc.perform(get("/api/carts/404"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.status").value("NOT_FOUND"));
  }

  @Test
  void addLine_returns409_onVersionConflict_withLatestCart() throws Exception {
    Cart latest = cart(200L, 5L, CartStatus.ACTIVE);
    CartLine line = CartLine.builder().cartUuid(200L).variantId(1001L).quantity(7).build();
    line.setUuid(500L);
    line.setVersion(0L);
    doThrow(new CartVersionConflictException(0L, 5L, latest))
        .when(addLineUseCase)
        .addLine(eq(200L), eq(1001L), eq(2), any());
    // Regression: the 409 latest-cart body MUST include its lines (AC #5). Verify by stubbing the
    // line repo to return one line and asserting it appears in the response.
    when(cartLineRepository.findByCartUuid(200L)).thenReturn(List.of(line));

    mvc.perform(
            post("/api/carts/200/lines")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":1001,\"quantity\":2,\"expectedCartVersion\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.status").value("CONFLICT"))
        .andExpect(jsonPath("$.message").value("Cart version conflict"))
        .andExpect(jsonPath("$.details.expectedVersion").value(0))
        .andExpect(jsonPath("$.details.actualVersion").value(5))
        .andExpect(jsonPath("$.details.cart.cartUuid").value(200))
        .andExpect(jsonPath("$.details.cart.version").value(5))
        .andExpect(jsonPath("$.details.cart.lines[0].variantId").value(1001))
        .andExpect(jsonPath("$.details.cart.lines[0].quantity").value(7));
  }

  @Test
  void updateLine_returns200() throws Exception {
    CartLine line = CartLine.builder().cartUuid(200L).variantId(1001L).quantity(3).build();
    line.setUuid(500L);
    line.setVersion(1L);
    when(updateLineQuantityUseCase.updateQuantity(eq(200L), eq(500L), eq(3), any()))
        .thenReturn(line);
    when(getOrCreateCartUseCase.findByUuid(200L)).thenReturn(cart(200L, 1L, CartStatus.ACTIVE));

    mvc.perform(
            patch("/api/carts/200/lines/500")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":3,\"expectedLineVersion\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cartUuid").value(200));
  }

  @Test
  void updateLine_returns404_whenLineMissing() throws Exception {
    doThrow(new CartLineNotFoundException(200L, 500L))
        .when(updateLineQuantityUseCase)
        .updateQuantity(eq(200L), eq(500L), eq(3), any());

    mvc.perform(
            patch("/api/carts/200/lines/500")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":3,\"expectedLineVersion\":0}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void removeLine_returns204() throws Exception {
    mvc.perform(delete("/api/carts/200/lines/500?expectedCartVersion=0"))
        .andExpect(status().isNoContent());
  }

  @Test
  void removeLine_returns409_onVersionConflict() throws Exception {
    Cart latest = cart(200L, 9L, CartStatus.ACTIVE);
    doThrow(new CartVersionConflictException(0L, 9L, latest))
        .when(removeLineUseCase)
        .removeLine(eq(200L), eq(500L), any());

    mvc.perform(delete("/api/carts/200/lines/500?expectedCartVersion=0"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.details.expectedVersion").value(0))
        .andExpect(jsonPath("$.details.actualVersion").value(9));
  }

  @Test
  void merge_returns400_onBlankFields() throws Exception {
    when(mergeCartUseCase.merge("g-1", "u-1"))
        .thenThrow(new IllegalArgumentException("both guestCartId and userId are required"));

    mvc.perform(
            post("/api/carts/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestCartId\":\"g-1\",\"userId\":\"u-1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }
}
