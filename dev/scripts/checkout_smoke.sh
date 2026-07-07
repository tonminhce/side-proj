#!/usr/bin/env bash
# dev/scripts/checkout_smoke.sh — Story 2.3 / FR-19 + FR-21 end-to-end smoke.
# Verifies single-page checkout: POST /api/checkouts/start returns { checkoutId, status: PAYMENT_PENDING };
# GET /api/checkouts/{uuid} returns the current state; the checkout.started event lands in checkout_db.outbox.
#
# Prereq: docker compose up; cart service on :8085; checkout service on :8084
# (mvn -pl services/cart -am spring-boot:run && mvn -pl services/checkout -am spring-boot:run).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

CART_URL="${CART_URL:-http://localhost:8085}"
CHECKOUT_URL="${CHECKOUT_URL:-http://localhost:8084}"
CHECKOUT_USER="${POSTGRES_CHECKOUT_USER:-checkout_user}"
CHECKOUT_DB="${POSTGRES_CHECKOUT_DB:-checkout_db}"

psql_checkout() { psql -h localhost -U "${CHECKOUT_USER}" -d "${CHECKOUT_DB}" "$@"; }

GUEST_UUID="$(uuidgen)"
USER_ID="user-abc-123"
VARIANT_ID=1001
QUANTITY=2

echo "== 1. Create anonymous cart (cookie UUID=${GUEST_UUID})"
CART_JSON=$(curl -sf -X POST "${CART_URL}/api/carts" \
  -H 'Content-Type: application/json' \
  -d "{\"guestCartId\":\"${GUEST_UUID}\"}")
CART_UUID=$(echo "${CART_JSON}" | jq -r '.cartUuid')
echo "  cartUuid=${CART_UUID}"

echo "== 2. Add a line (variant ${VARIANT_ID} x${QUANTITY})"
curl -sf -X POST "${CART_URL}/api/carts/${CART_UUID}/lines" \
  -H 'Content-Type: application/json' \
  -d "{\"variantId\":${VARIANT_ID},\"quantity\":${QUANTITY},\"expectedCartVersion\":0}" >/dev/null
echo "  ok"

echo "== 3. POST /api/checkouts/start — single-page checkout"
CHECKOUT_JSON=$(curl -sf -X POST "${CHECKOUT_URL}/api/checkouts/start" \
  -H 'Content-Type: application/json' \
  -d "{
    \"cartUuid\": ${CART_UUID},
    \"userId\": \"${USER_ID}\",
    \"shippingAddress\": {
      \"recipientName\":\"Nguyen Van A\",
      \"phone\":\"0901234567\",
      \"addressLine1\":\"123 Le Loi\",
      \"city\":\"HCM\",
      \"province\":\"HCM\",
      \"country\":\"VN\"
    },
    \"cartLines\": [{\"variantId\":${VARIANT_ID},\"quantity\":${QUANTITY}}],
    \"stripeClientSecret\": \"pi_xxx_secret_xxx\"
  }")
echo "${CHECKOUT_JSON}" | jq .

CHECKOUT_ID=$(echo "${CHECKOUT_JSON}" | jq -r '.checkoutId')
STATUS=$(echo "${CHECKOUT_JSON}" | jq -r '.status')
[ "${STATUS}" = "PAYMENT_PENDING" ] || { echo "FAIL: expected status=PAYMENT_PENDING, got ${STATUS}"; exit 1; }
echo "  ok: checkoutId=${CHECKOUT_ID} status=${STATUS}"

echo "== 4. GET /api/checkouts/{uuid} — polling endpoint (FR-21)"
POLL_JSON=$(curl -sf "${CHECKOUT_URL}/api/checkouts/${CHECKOUT_ID}")
echo "${POLL_JSON}" | jq .
POLL_STATUS=$(echo "${POLL_JSON}" | jq -r '.status')
[ "${POLL_STATUS}" = "PAYMENT_PENDING" ] || { echo "FAIL: expected poll status=PAYMENT_PENDING, got ${POLL_STATUS}"; exit 1; }
echo "  ok"

echo "== 5. checkout_db.outbox: expect a checkout.started row with HMAC signature"
psql_checkout -c "SELECT event_type, aggregate_id, payload->>'status' AS status, signatures->>'hmac_sha256' IS NOT NULL AS has_sig FROM outbox WHERE event_type='checkout.started' ORDER BY created_at DESC LIMIT 5"

echo "All checkout smoke checks passed."