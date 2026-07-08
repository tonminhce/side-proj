-- V002__create_customer_data_registry.sql — Story 5.2 / FR-46, FR-49 (solves LC-01)
-- Per-service registry of (service, table, columns, format) so the cross-service export job
-- knows what to fetch. The customer service seeds 2 rows for the customer + address tables.
-- Future services seed their own rows on startup.

CREATE TABLE customer_data_registry (
    id           BIGSERIAL PRIMARY KEY,
    service_name VARCHAR(64) NOT NULL,
    table_name   VARCHAR(64) NOT NULL,
    columns      JSONB NOT NULL,
    format       VARCHAR(16) NOT NULL DEFAULT 'json',
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_data_registry_service ON customer_data_registry(service_name);

-- Seed rows for the customer service (sprint-5.2 ships the customer-side export; future stories
-- append the forget_audit row at delete time).
INSERT INTO customer_data_registry (service_name, table_name, columns, format) VALUES
  ('customer_service', 'customer',
   '["id","user_id","display_name","email","phone","created_at"]'::jsonb, 'json'),
  ('customer_service', 'address',
   '["id","customer_id","line1","province_code","district_code","commune_code","is_default","created_at"]'::jsonb, 'json');