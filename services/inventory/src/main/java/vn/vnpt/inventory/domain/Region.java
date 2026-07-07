package vn.vnpt.inventory.domain;

/**
 * Region routing key for FR-10 multi-warehouse reservation dispatch. Saga callers supply
 * the customer's shipping region; the picker selects the in-region warehouse with enough
 * stock. Same single-tenant convention as {@link ReservationStatus} / {@link InventoryReason}:
 * enum {@code name()} matches the DB string verbatim — see {@code V005.chk_warehouses_region}.
 *
 * <p>v1 uses region as the proximity proxy (ADR-06 multi-warehouse stretch). Distance-based
 * scoring (postal code → warehouse distance matrix) is a Future Story 8.x admin optimization.
 */
public enum Region {
  NORTH,
  SOUTH,
  CENTRAL
}