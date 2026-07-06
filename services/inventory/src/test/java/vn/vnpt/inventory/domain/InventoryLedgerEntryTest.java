package vn.vnpt.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit tests for {@link InventoryLedgerEntry} — no Spring context. Pins three invariants:
 * (a) the builder carries all fields; (b) Lombok's {@code @EqualsAndHashCode(callSuper=true)} is
 * field-by-field; (c) the {@code eventId} setter is absent — the immutable idempotency key.
 */
class InventoryLedgerEntryTest {

  @Test
  void entry_carriesAllFields() {
    InventoryLedgerEntry entry =
        InventoryLedgerEntry.builder()
            .variantId(100L)
            .warehouseId(200L)
            .delta(5L)
            .reason("receive")
            .eventId(123L)
            .tenantId("default")
            .build();

    assertThat(entry.getVariantId()).isEqualTo(100L);
    assertThat(entry.getWarehouseId()).isEqualTo(200L);
    assertThat(entry.getDelta()).isEqualTo(5L);
    assertThat(entry.getReason()).isEqualTo("receive");
    assertThat(entry.getEventId()).isEqualTo(123L);
    assertThat(entry.getTenantId()).isEqualTo("default");
  }

  @Test
  void equals_isFieldBased() {
    InventoryLedgerEntry a = baseEntry();
    InventoryLedgerEntry b = baseEntry();
    InventoryLedgerEntry c = baseEntry();
    c.setDelta(99L); // distinct from a, b

    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(c);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  /**
   * The {@code eventId} field is marked {@code @Setter(AccessLevel.NONE)} so no setter is
   * generated. Verify the absence via reflection — a regression that flips the access level
   * would silently re-enable mutation of the idempotency key.
   */
  @Test
  void eventIdSetterIsAbsent() throws NoSuchMethodException {
    // The Lombok-generated setter for `eventId` would be named `setEventId`. Its absence is
    // the invariant we pin.
    Method setter = null;
    try {
      setter = InventoryLedgerEntry.class.getDeclaredMethod("setEventId", Long.class);
    } catch (NoSuchMethodException expected) {
      // Expected — the setter is absent.
    }
    assertThat(setter).as("setEventId(Long) must not exist").isNull();
  }

  private static InventoryLedgerEntry baseEntry() {
    return InventoryLedgerEntry.builder()
        .variantId(100L)
        .warehouseId(200L)
        .delta(0L)
        .reason("receive")
        .eventId(42L)
        .tenantId("default")
        .build();
  }
}