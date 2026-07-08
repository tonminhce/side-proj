-- V003__add_address_to_order_price_snapshot.sql — Story 4.4 / FR-34
-- Adds the address_json column for the edit-after-pay use case. The column is nullable for
-- existing rows; new orders capture the address at order.placed (or after amend).
--
-- FR-31 immutability: the column is INSERT-only at the application layer (the AmendOrderAddressUseCase
-- is the only writer; the price columns stay immutable).

ALTER TABLE order_price_snapshot ADD COLUMN address_json JSONB;