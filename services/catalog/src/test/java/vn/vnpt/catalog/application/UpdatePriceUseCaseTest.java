package vn.vnpt.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.catalog.CatalogApplication;
import vn.vnpt.catalog.domain.Product;
import vn.vnpt.catalog.domain.Variant;
import vn.vnpt.catalog.domain.exception.VariantNotFoundException;

/**
 * Full Spring context integration tests for {@link UpdatePriceUseCase} (Story 1.3 /
 * AC #11 / Subtask 9.8).
 *
 * <p>Pins two invariants:
 *
 * <ol>
 *   <li>Payload contains BOTH old and new price (consumers need the diff for UI).
 *   <li>Old price is captured BEFORE mutation (regression for AC #11 ponytail).
 * </ol>
 */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class UpdatePriceUseCaseTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("catalog_db")
          .withUsername("catalog_user")
          .withPassword("catalog_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired UpdatePriceUseCase useCase;
  @Autowired CreateProductUseCase createUseCase;
  @Autowired DataSource dataSource;
  @Autowired ObjectMapper objectMapper;

  @Test
  void updatePrice_persistsNewPriceAndOutboxEvent() {
    Variant variant = createVariantWithPrice(199_000L, "sku-price-1");

    Variant updated = useCase.updatePrice(new UpdatePriceCommand(variant.getUuid(), 249_000L));

    assertThat(updated.getPriceCents()).isEqualTo(249_000L);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type ="
                + " 'catalog.product.price_changed'",
            String.class,
            variant.getUuid());

    JsonNode node = parseJson(payload);
    assertThat(node.get("oldPriceCents").asLong()).isEqualTo(199_000L);
    assertThat(node.get("newPriceCents").asLong()).isEqualTo(249_000L);
    assertThat(node.get("variantUuid").asLong()).isEqualTo(variant.getUuid());
  }

  @Test
  void updatePrice_capturesOldPriceBeforeMutation() {
    // Regression: a buggy implementation that captures oldPriceCents AFTER variant.setPriceCents
    // would store oldPriceCents = 249_000L, making the diff meaningless. Pin 199_000L.
    Variant variant = createVariantWithPrice(199_000L, "sku-mutation-order-1");

    useCase.updatePrice(new UpdatePriceCommand(variant.getUuid(), 249_000L));

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type ="
                + " 'catalog.product.price_changed'",
            String.class,
            variant.getUuid());

    JsonNode node = parseJson(payload);
    assertThat(node.get("oldPriceCents").asLong()).isEqualTo(199_000L);
  }

  @Test
  void updatePrice_throwsWhenVariantNotFound() {
    assertThatThrownBy(() -> useCase.updatePrice(new UpdatePriceCommand(999_999_999L, 100L)))
        .isInstanceOf(VariantNotFoundException.class);
  }

  @Test
  void updatePrice_rejectsNegativePrice() {
    Variant variant = createVariantWithPrice(100L, "sku-neg-1");
    assertThatThrownBy(
            () -> useCase.updatePrice(new UpdatePriceCommand(variant.getUuid(), -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-negative");
  }

  private Variant createVariantWithPrice(long priceCents, String sku) {
    Product created =
        createUseCase.create(
            new CreateProductCommand(
                "Price Test",
                sku,
                null,
                null,
                List.of(
                    new CreateProductCommand.VariantSpec(
                        Map.of("color", "red"), priceCents, "VND")),
                List.of()));
    return new JdbcTemplate(dataSource)
        .queryForObject(
            "SELECT uuid, product_uuid, sku, price_cents, currency FROM variants WHERE"
                + " product_uuid = ? LIMIT 1",
            (rs, i) -> {
              Variant v = new Variant();
              v.setUuid(rs.getLong("uuid"));
              v.setProductUuid(rs.getLong("product_uuid"));
              v.setSku(rs.getString("sku"));
              v.setPriceCents(rs.getLong("price_cents"));
              v.setCurrency(rs.getString("currency"));
              return v;
            },
            created.getUuid());
  }

  private JsonNode parseJson(String s) {
    try {
      return objectMapper.readTree(s);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
