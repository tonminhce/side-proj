package vn.vnpt.inventory.domain.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReservation;

/**
 * Pure-JUnit pin for the {@code @IgnoreSoftUkAudit} opt-out — Story 1.8 / FR-12 / DI-09 fix.
 *
 * <p>Two entities legitimately lack a soft-delete uniqueness invariant:
 *
 * <ul>
 *   <li>{@link InventoryLedgerEntry} — append-only ledger (Story 1.5 convention)
 *   <li>{@link InventoryReservation} — terminal-only state machine (Story 1.6 convention)
 * </ul>
 *
 * <p>The boundary test {@code inventory_softDeletableEntitiesHaveSoftUkAnnotation} catches
 * the absence transitively (a missing opt-out would require {@code @SoftUk} on the entity
 * and fail the build). This test pins the opt-out POSITIVELY at the class level so a
 * regression that drops the annotation — or applies it to the wrong entity — fails at unit
 * time, not at ArchUnit-classpath-scan time.
 */
class IgnoreSoftUkAuditTest {

  @Test
  void inventoryLedgerEntry_carriesIgnoreSoftUkAuditMarker() {
    IgnoreSoftUkAudit marker =
        InventoryLedgerEntry.class.getAnnotation(IgnoreSoftUkAudit.class);
    assertThat(marker)
        .as(
            "@IgnoreSoftUkAudit on InventoryLedgerEntry — append-only aggregate"
                + " (Story 1.5 convention); see JavaDoc on the entity for opt-out rationale")
        .isNotNull();
  }

  @Test
  void inventoryReservation_carriesIgnoreSoftUkAuditMarker() {
    IgnoreSoftUkAudit marker =
        InventoryReservation.class.getAnnotation(IgnoreSoftUkAudit.class);
    assertThat(marker)
        .as(
            "@IgnoreSoftUkAudit on InventoryReservation — terminal-only state machine"
                + " (Story 1.6 convention); see JavaDoc on the entity for opt-out rationale")
        .isNotNull();
  }

  /**
   * Marker contract — the annotation is {@code @Retention(RUNTIME)} so the ArchUnit
   * boundary test's {@code areNotAnnotatedWith(IgnoreSoftUkAudit.class)} predicate can read
   * it at classpath-scan time. A regression that drops {@code RUNTIME} retention would
   * silently break the opt-out (annotation would vanish at runtime) without failing the
   * compile.
   */
  @Test
  void markerHasRuntimeRetention() {
    java.lang.annotation.Retention retention =
        IgnoreSoftUkAudit.class.getAnnotation(java.lang.annotation.Retention.class);
    assertThat(retention).isNotNull();
    assertThat(retention.value()).isEqualTo(java.lang.annotation.RetentionPolicy.RUNTIME);
  }

  /**
   * Marker contract — the annotation targets {@code ElementType.TYPE} so it can be applied
   * at the class level (the entity). A regression that restricts the target to e.g.
   * {@code ElementType.FIELD} would break the opt-out pattern at compile time.
   */
  @Test
  void markerTargetsType() {
    java.lang.annotation.Target target =
        IgnoreSoftUkAudit.class.getAnnotation(java.lang.annotation.Target.class);
    assertThat(target).isNotNull();
    assertThat(java.util.Arrays.asList(target.value()))
        .contains(java.lang.annotation.ElementType.TYPE);
  }
}