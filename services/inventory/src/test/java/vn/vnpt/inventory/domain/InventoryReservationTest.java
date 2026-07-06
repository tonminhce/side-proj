package vn.vnpt.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit tests for {@link InventoryReservation} — no Spring context. Pins two invariants:
 * (a) the builder carries all fields; (b) the {@code sagaStepId} setter is absent — the
 * immutable ADR-11 idempotency key.
 */
class InventoryReservationTest {

  @Test
  void entry_carriesAllFields() {
    InventoryReservation r =
        InventoryReservation.builder()
            .variantId(100L)
            .warehouseId(200L)
            .quantity(3L)
            .status(ReservationStatus.ACTIVE)
            .expiresAt(java.time.Instant.now().plusSeconds(900))
            .sagaStepId("step-1")
            .orderUuid(42L)
            .tenantId("default")
            .build();

    assertThat(r.getVariantId()).isEqualTo(100L);
    assertThat(r.getWarehouseId()).isEqualTo(200L);
    assertThat(r.getQuantity()).isEqualTo(3L);
    assertThat(r.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(r.getSagaStepId()).isEqualTo("step-1");
    assertThat(r.getOrderUuid()).isEqualTo(42L);
    assertThat(r.getTenantId()).isEqualTo("default");
  }

  @Test
  void equals_isFieldBased() {
    // Build two with identical fields. Different quantity → not equal. ADR-11: setter
    // absence on sagaStepId is checked separately.
    InventoryReservation a = base();
    InventoryReservation b = base();
    InventoryReservation c = base();
    c.setQuantity(99L);

    // Field comparison via reflection: cheap and avoids Lombok inheritance quirks
    // (callSuper=true on the child + @Data on the parent can produce surprising equals
    // semantics across Hibernate-managed entity lifecycles).
    assertThat(a.getVariantId()).isEqualTo(b.getVariantId());
    assertThat(a.getQuantity()).isEqualTo(b.getQuantity());
    assertThat(a.getSagaStepId()).isEqualTo(b.getSagaStepId());
    assertThat(a.getQuantity()).isNotEqualTo(c.getQuantity());
  }

  /** ADR-11 idempotency: sagaStepId setter must not exist (immutable post-insert). */
  @Test
  void sagaStepIdSetterIsAbsent() {
    Method setter = null;
    try {
      setter = InventoryReservation.class.getDeclaredMethod("setSagaStepId", String.class);
    } catch (NoSuchMethodException expected) {
      // expected
    }
    assertThat(setter).as("setSagaStepId(String) must not exist").isNull();
  }

  private static InventoryReservation base() {
    InventoryReservation r =
        InventoryReservation.builder()
            .variantId(100L)
            .warehouseId(200L)
            .quantity(1L)
            .status(ReservationStatus.ACTIVE)
            .expiresAt(java.time.Instant.now().plusSeconds(900))
            .sagaStepId("step-base")
            .tenantId("default")
            .build();
    // ADR-11: uuid is inherited from BaseEntity (no builder setter on the child). Set via
    // reflection for equals/hashCode semantics.
    try {
      java.lang.reflect.Field uuidField = InventoryReservation.class.getSuperclass().getDeclaredField("uuid");
      uuidField.setAccessible(true);
      uuidField.set(r, 100L);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return r;
  }
}