package vn.vnpt.inventory.domain;

/**
 * Inventory adjustment reason — Story 1.5 / FR-8.
 *
 * <p>Each value maps to a {@code reason VARCHAR(64)} lowercase string in the DB:
 * {@code RECEIVE -> "receive"}, {@code ADJUST -> "adjust"}, etc. The mapping is intentionally a
 * code-side concern: adding a new reason type is a code change, NOT a database migration
 * (FR-8 reconciliation relies on append-only history; new reasons don't break old rows).
 */
public enum InventoryReason {

  /** Inbound stock from a supplier. Sign: {@code +N}. */
  RECEIVE,

  /** Manual correction (count discrepancy, lost-in-warehouse, damaged). Sign: any. */
  ADJUST,

  /** Cart-side reservation; Story 1.6 wires the {@code SELECT … FOR UPDATE} path. Sign: {@code -N}. */
  RESERVE,

  /** Release of a reservation (cart cancelled). Sign: {@code +N}. */
  RELEASE,

  /** Allocation of reserved stock to a confirmed order. Sign: {@code -N}. */
  ALLOCATE,

  /** Shipment picked from the warehouse. Sign: {@code -N}. */
  SHIP;

  /** Returns the lowercase column value stored in {@code inventory_ledger.reason}. */
  public String toColumnValue() {
    return name().toLowerCase();
  }
}