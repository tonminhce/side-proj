package vn.vnpt.cart.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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
import vn.vnpt.cart.CartApplication;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;

/** Integration test for {@link CartLineRepository} — Story 2.1 (Testcontainers Postgres). */
@SpringBootTest(classes = CartApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CartLineRepositoryTest {

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

  @Autowired CartRepository cartRepository;
  @Autowired CartLineRepository cartLineRepository;
  @Autowired DataSource dataSource;

  private Long cartUuid;

  @BeforeEach
  void seedCart() {
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE cart_merge_log, cart_lines, carts RESTART IDENTITY CASCADE");
    cartUuid =
        cartRepository
            .save(
                Cart.builder()
                    .tenantId("default")
                    .guestCartId("g-line-" + System.nanoTime())
                    .status(CartStatus.ANONYMOUS)
                    .build())
            .getUuid();
  }

  @Test
  void save_andFindByCartUuid() {
    cartLineRepository.save(
        CartLine.builder().cartUuid(cartUuid).tenantId("default").variantId(1001L).quantity(2).build());
    assertThat(cartLineRepository.findByCartUuid(cartUuid)).hasSize(1);
    assertThat(cartLineRepository.findByCartUuidAndVariantId(cartUuid, 1001L)).isPresent();
  }

  @Test
  void softDelete_keepsRow_butFindersExclude() {
    CartLine line =
        cartLineRepository.save(
            CartLine.builder().cartUuid(cartUuid).tenantId("default").variantId(2002L).quantity(1).build());

    line.setIsDeleted(true);
    line.setIsActive(false);
    cartLineRepository.save(line);

    // Row is kept (append-only philosophy) ...
    assertThat(cartLineRepository.findById(line.getUuid())).isPresent();
    assertThat(cartLineRepository.findById(line.getUuid()).get().getIsDeleted()).isTrue();
    // ... but the active-line projection (response boundary) excludes it.
    long active =
        cartLineRepository.findByCartUuid(cartUuid).stream()
            .filter(l -> !Boolean.TRUE.equals(l.getIsDeleted()))
            .count();
    assertThat(active).isZero();
  }
}
