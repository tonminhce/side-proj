package vn.vnpt.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.vnpt.util.component.softdelete.annotation.SoftUk;

/** Pure-JUnit sanity check for {@link Warehouse} Lombok builder. Extended in Story 1.7 with {@code region}. Story 1.8 adds {@code @SoftUk} reflection check. */
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

  /**
   * Story 1.8 / FR-12 / DI-09 regression guard — {@link Warehouse} MUST carry the
   * {@code @SoftUk(name="warehouse_code_per_tenant", fields={"tenantId","code"})} annotation.
   * Read from the class via reflection so a removed annotation fails the test before the
   * ArchUnit boundary test runs.
   */
  @Test
  void softUkAnnotationIsPresentAtClassLevel() {
    SoftUk softUk = Warehouse.class.getAnnotation(SoftUk.class);
    assertThat(softUk).as("@SoftUk on Warehouse").isNotNull();
    assertThat(softUk.name()).isEqualTo("warehouse_code_per_tenant");
    assertThat(softUk.fields()).containsExactly("tenantId", "code");
  }
}