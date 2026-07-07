package vn.vnpt.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Sanity check for {@link Region} — mirrors {@link ReservationStatusTest} / {@link InventoryReasonTest}.
 */
class RegionTest {

  @Test
  void enumValues_matchCheckConstraint() {
    assertThat(Region.values()).containsExactly(Region.NORTH, Region.SOUTH, Region.CENTRAL);
    // The DB CHECK constraint `chk_warehouses_region` (V005) accepts exactly these names.
    assertThat(Region.NORTH.name()).isEqualTo("NORTH");
    assertThat(Region.SOUTH.name()).isEqualTo("SOUTH");
    assertThat(Region.CENTRAL.name()).isEqualTo("CENTRAL");
  }

  @Test
  void valueOf_roundTrips() {
    assertThat(Region.valueOf("NORTH")).isEqualTo(Region.NORTH);
    assertThat(Region.valueOf("SOUTH")).isEqualTo(Region.SOUTH);
    assertThat(Region.valueOf("CENTRAL")).isEqualTo(Region.CENTRAL);
  }
}