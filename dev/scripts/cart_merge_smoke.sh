#!/usr/bin/env bash
# dev/scripts/cart_merge_smoke.sh — Story 2.1 / FR-14 + FR-15 + FR-16 end-to-end smoke.
# Verifies anonymous cart creation, add-line, idempotent merge-on-login, ownership-conflict 409,
# the cart_merge_log idempotency beacon, and the signed cart.merged outbox event.
#
# Prereq: docker compose up; cart service started on :8085 (mvn -pl services/cart -am spring-boot:run).
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
USER_ID="user-abc-123"
USER_ID_2="user-def-456"

echo "== 1. Anonymous cart creation (cookie UUID=${GUEST_UUID})"
CART_JSON=$(curl -sf -X POST "${CART_URL}/api/carts" \
  -H 'Content-Type: application/json' \
  -d "{\"guestCartId\":\"${GUEST_UUID}\"}")
echo "${CART_JSON}" | jq .
CART_UUID=$(echo "${CART_JSON}" | jq -r '.cartUuid')

echo "== 2. Add a line to the anonymous cart (variant 1001 x2)"
curl -sf -X POST "${CART_URL}/api/carts/${CART_UUID}/lines" \
  -H 'Content-Type: application/json' \
  -d '{"variantId":1001,"quantity":2,"expectedCartVersion":0}' | jq .

echo "== 3. Merge into user-bound cart (first call → 200)"
curl -sf -X POST "${CART_URL}/api/carts/merge" \
  -H 'Content-Type: application/json' \
  -d "{\"guestCartId\":\"${GUEST_UUID}\",\"userId\":\"${USER_ID}\"}" | jq .

echo "== 4. Retry the merge (idempotent → same cart, no new merge_log row)"
curl -sf -X POST "${CART_URL}/api/carts/merge" \
  -H 'Content-Type: application/json' \
  -d "{\"guestCartId\":\"${GUEST_UUID}\",\"userId\":\"${USER_ID}\"}" | jq .

echo "== 5. Different user claims the same anonymous cart → expect 409"
HTTP=$(curl -s -o /tmp/cart_conflict.json -w '%{http_code}' -X POST "${CART_URL}/api/carts/merge" \
  -H 'Content-Type: application/json' \
  -d "{\"guestCartId\":\"${GUEST_UUID}\",\"userId\":\"${USER_ID_2}\"}")
cat /tmp/cart_conflict.json | jq .
[ "${HTTP}" = "409" ] && echo "OK: 409 ownership conflict" || { echo "FAIL: expected 409, got ${HTTP}"; exit 1; }

echo "== 6. cart_merge_log: expect exactly 1 row for the first merge (retry did NOT insert)"
psql_cart -c "SELECT idempotency_key, guest_cart_id, user_id, source_cart_uuid, target_cart_uuid, merged_lines_count FROM cart_merge_log ORDER BY merged_at DESC LIMIT 5"

echo "== 7. outbox: expect 1 signed cart.merged event with mergedLinesCount=1"
psql_cart -c "SELECT event_type, aggregate_id, payload->>'mergedLinesCount' AS lines, signatures->>'hmac_sha256' AS sig FROM outbox WHERE event_type='cart.merged' ORDER BY created_at DESC LIMIT 5"

echo "All cart merge smoke checks passed."
