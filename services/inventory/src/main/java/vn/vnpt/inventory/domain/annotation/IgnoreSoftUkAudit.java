package vn.vnpt.inventory.domain.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for entities that legitimately lack {@code @SoftUk} — Story 1.8 / FR-12 (DI-09 fix).
 *
 * <p>The ArchUnit rule {@code inventory_softDeletableEntitiesHaveSoftUkAnnotation} requires
 * every JPA entity extending {@code RootEntity} to carry a {@code @SoftUk} (or {@code @SoftUks})
 * annotation, otherwise the build fails. Entities that follow an append-only or
 * terminal-only philosophy do NOT need a soft-uniqueness invariant — this marker is the
 * explicit opt-out.
 *
 * <p>Allowed cases:
 *
 * <ul>
 *   <li><b>Append-only aggregates</b> (e.g. {@code InventoryLedgerEntry}) — rows are never
 *       modified or soft-deleted; the convention is structural.
 *   <li><b>Terminal-only state machines</b> (e.g. {@code InventoryReservation}) — rows
 *       transition through {@code ReservationStatus} values, never row-deleted.
 * </ul>
 *
 * <p>Every use of this annotation MUST include a JavaDoc justification on the entity class
 * explaining the opt-out rationale.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IgnoreSoftUkAudit {}