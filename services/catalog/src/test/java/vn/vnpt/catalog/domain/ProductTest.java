package vn.vnpt.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure-JUnit tests for {@link Product}. AC #5 / Subtask 9.2. */
class ProductTest {

  @Test
  void create_populatesAllFields() {
    Product product = Product.create("name", "sku-1", "a description", "Acme");
    assertThat(product.getName()).isEqualTo("name");
    assertThat(product.getSku()).isEqualTo("sku-1");
    assertThat(product.getDescription()).isEqualTo("a description");
    assertThat(product.getBrand()).isEqualTo("Acme");
    // uuid and audit fields are null until BaseEntity.prePersist() runs at insert time.
    assertThat(product.getUuid()).isNull();
  }

  /**
   * Lombok's {@code @EqualsAndHashCode(callSuper = true)} is a field-by-field comparison: two
   * entities with the same uuid AND identical fields compare equal. Entities with the same uuid
   * but different fields are NOT equal under Lombok's default semantics — that's a Hibernate
   * "entities are equal by id" override the story does not require. This test pins Lombok's
   * behavior so a future refactor (custom equals, etc.) surfaces in review.
   */
  @Test
  void equals_isFieldBased() {
    Product a = Product.create("name", "sku", "desc", "Acme");
    Product b = Product.create("name", "sku", "desc", "Acme");
    a.setUuid(42L);
    b.setUuid(42L);
    assertThat(a).isEqualTo(b);

    Product different = Product.create("name", "sku", "desc", "Other");
    different.setUuid(42L);
    assertThat(a).isNotEqualTo(different);
  }
}
