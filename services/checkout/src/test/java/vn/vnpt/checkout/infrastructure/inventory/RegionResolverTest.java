package vn.vnpt.checkout.infrastructure.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.inventory.domain.Region;

/**
 * Story 2.5 / FR-22 — {@code ShippingAddress → Region} stub mapping. Covers the saga's
 * inventory-port region resolution; addresses outside the resolver return {@code null} and the
 * orchestrator's catch block marks the order FAILED.
 */
class RegionResolverTest {

  private final RegionResolver resolver = new RegionResolver();

  @Test
  void hanoiProvince_returnsNorth() {
    assertThat(resolver.resolve(addr("Hà Nội"))).isEqualTo(Region.NORTH);
  }

  @Test
  void hcmProvince_returnsSouth() {
    assertThat(resolver.resolve(addr("Hồ Chí Minh"))).isEqualTo(Region.SOUTH);
  }

  @Test
  void danangProvince_returnsCentral() {
    assertThat(resolver.resolve(addr("Đà Nẵng"))).isEqualTo(Region.CENTRAL);
  }

  @Test
  void shortProvinceAliases_resolveCorrectly() {
    assertThat(resolver.resolve(addr("hn"))).isEqualTo(Region.NORTH);
    assertThat(resolver.resolve(addr("HCM"))).isEqualTo(Region.SOUTH);
  }

  @Test
  void unknownProvince_returnsNull() {
    assertThat(resolver.resolve(addr("Mendoza"))).isNull();
  }

  @Test
  void nullAddress_returnsNull() {
    assertThat(resolver.resolve(null)).isNull();
  }

  @Test
  void nullProvince_returnsNull() {
    assertThat(resolver.resolve(ShippingAddress.builder().build())).isNull();
  }

  private ShippingAddress addr(String province) {
    return ShippingAddress.builder()
        .recipientName("A").phone("0").addressLine1("a")
        .city("x").province(province).country("VN")
        .build();
  }
}