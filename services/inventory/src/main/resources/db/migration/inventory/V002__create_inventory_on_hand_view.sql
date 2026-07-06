-- V002__create_inventory_on_hand_view.sql — Story 1.5 (AC #10)
-- Read-side view for ad-hoc DBA inspection of inventory on_hand. The production read path
-- uses the JPA-derived COALESCE(SUM(...), 0) query in InventoryLedgerEntryRepository (faster,
-- type-safe, no view round-trip). The view is a debugging surface — see OnHandUseCase JavaDoc.

CREATE OR REPLACE VIEW inventory_on_hand AS
SELECT
    variant_id,
    warehouse_id,
    SUM(delta)      AS on_hand,
    COUNT(*)        AS entry_count,
    MAX(created_at) AS last_movement_at
FROM inventory_ledger
GROUP BY variant_id, warehouse_id;