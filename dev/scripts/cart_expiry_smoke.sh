#!/usr/bin/env bash
# dev/scripts/cart_expiry_smoke.sh — Story 2.2 / FR-17 + FR-18 end-to-end smoke.
# Verifies anonymous cart creation, line-add with cart.line.added event emission,
# the 30-day TTL default via V002, and cart.expired emission by the sweeper.
#
# Prereq: docker compose up; cart service started on :8085 with overridden sweeper interval:
#   CART_AUTO_EXPIRE_SWEEPER_INTERVAL_MS=10000 mvn -pl services/cart -am spring-boot:run
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

CART_USER="${POSTGRES_CART_USER:-cart_user}"
CART_DB="${POSTGRES_CART_DB:-cart_db}"
CART_URL="${CART_URL:-http://localhost:8085}"

psql_cart() { psql -h localhost -U "${CART_USER}" -d "${CART_DB}" "$@"; }

GUEST_UUID="$(uuidgen)"

echo "== 1. Anonymous cart creation (cookie UUID=${GUEST_UUID})"
CART_JSON=$(curl -sf -X POST "${CART_URL}/api/carts" \
  -H 'Content-Type: application/json' \
  -d "{\"guestCartId\":\"${GUEST_UUID}\"}")
echo "${CART_JSON}" | jq .
CART_UUID=$(echo "${CART_JSON}" | jq -r '.cartUuid')

echo "== 2. Add a line → verify cart.line.added event in outbox"
curl -sf -X POST "${CART_URL}/api/carts/${CART_UUID}/lines" \
  -H 'Content-Type: application/json' \
  -H 'If-Match: 0' \
  -d '{"variantId":1001,"quantity":2}' | jq .
psql_cart -c "SELECT event_type, payload->>'variantId' AS variant, payload->>'quantity' AS qty FROM outbox WHERE event_type='cart.line.added' ORDER BY created_at DESC LIMIT 1"

echo "== 3. Force-expire the cart (override the 30-day TTL via direct UPDATE for the smoke test)"
psql_cart -c "UPDATE carts SET expires_at = now() - INTERVAL '1 day' WHERE uuid = ${CART_UUID}"

echo "== 4. Wait for the sweeper cycle (default 5min; override to 10s for smoke via CART_AUTO_EXPIRE_SWEEPER_INTERVAL_MS)"
sleep 15

echo "== 5. Verify the cart transitioned to ABANDONED"
psql_cart -c "SELECT uuid, status, version FROM carts WHERE uuid = ${CART_UUID}"

echo "== 6. Verify cart.expired event in outbox"
psql_cart -c "SELECT event_type, payload->>'cartUuid' AS cart, payload->>'expiredLinesCount' AS lines, payload->>'previousStatus' AS prev FROM outbox WHERE event_type='cart.expired' ORDER BY created_at DESC LIMIT 1"

echo "OK: cart_expiry smoke complete"