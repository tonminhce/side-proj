package vn.vnpt.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure-JUnit sanity check for {@link Warehouse} Lombok builder. Extended in Story 1.7 with {@code region}. */
class WarehouseTest {

  @Test
  void builder_setsAllFields() {
    Warehouse warehouse =
        Warehouse.builder()
            .code("HCM-01")
            .displayName("Ho Chi Minh")
            .region(Region.SOUTH)
            .build();

    assertThat(warehouse.getCode()).isEqualTo("HCM-01");
    assertThat(warehouse.getDisplayName()).isEqualTo("Ho Chi Minh");
    assertThat(warehouse.getRegion()).isEqualTo(Region.SOUTH);
  }
}