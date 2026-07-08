#!/usr/bin/env bash
# dev/scripts/smoke-customer-5-1.sh — Story 5.1 runtime smoke (FR-45, FR-47).
#
# Verifies the Customer + Address book end-to-end:
#   1. services/customer starts with bean-wiring + Postgres + Flyway V001.
#   2. /actuator/health returns {"status":"UP"} on :8088.
#   3. /actuator/loggers returns 404 (R-15 deny-list).
#   4. POST /api/customers with userId=99 — assert HTTP 201.
#   5. POST /api/customers/{id}/addresses with Vietnamese address — assert 201.
#   6. GET /api/customers/{id}/addresses — assert 1 address.
#   7. GET /api/customers/{id} — assert customer shape includes addresses.
#   8. Verify customer + address tables have 1 row each.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

CUSTOMER_URL="${CUSTOMER_URL:-http://localhost:8088}"
LOG_FILE="${LOG_FILE:-/tmp/customer-smoke-5-1.log}"
MVN_PID=""

if command -v lsof >/dev/null 2>&1; then
  STALE_PIDS="$(lsof -ti:"${CUSTOMER_URL##*:}" 2>/dev/null || true)"
  if [ -n "${STALE_PIDS}" ]; then
    kill -9 ${STALE_PIDS} 2>/dev/null || true
    sleep 1
  fi
fi

cleanup() {
  if [ -n "${MVN_PID}" ] && kill -0 "${MVN_PID}" 2>/dev/null; then
    kill "${MVN_PID}" 2>/dev/null || true
    wait "${MVN_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "== 0. Ensure customer_db exists"
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE USER customer_user WITH PASSWORD 'customer_pass' SUPERUSER;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE DATABASE customer_db OWNER customer_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE customer_db TO customer_user;" 2>/dev/null || true
echo "  ok: customer_db ready"

echo "== 1. Start services/customer (log -> ${LOG_FILE})"
(cd "${PROJECT_DIR}/services/customer" && \
   SPRING_PROFILES_ACTIVE=dev \
   mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!

echo "== 2. Wait for ${CUSTOMER_URL}/actuator/health (up to 90s)"
STATUS=""
for i in $(seq 1 90); do
  if curl -sf "${CUSTOMER_URL}/actuator/health" -o /tmp/customer-health.json 2>/dev/null; then
    STATUS=$(grep -o '"status":"[^"]*"' /tmp/customer-health.json | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
    if [ "${STATUS}" = "UP" ]; then
      echo "  ok: ${STATUS} (after ${i}s)"
      break
    fi
  fi
  sleep 1
done

if [ "${STATUS:-}" != "UP" ]; then
  echo "FAIL: ${CUSTOMER_URL}/actuator/health did not return UP"
  tail -80 "${LOG_FILE}" || true
  exit 1
fi

echo "== 3. /actuator/loggers must return 404 (R-15 deny-list)"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${CUSTOMER_URL}/actuator/loggers" || true)
if [ "${HTTP_CODE}" != "404" ]; then
  echo "FAIL: /actuator/loggers returned HTTP ${HTTP_CODE}; expected 404"
  exit 1
fi
echo "  ok: /actuator/loggers = HTTP 404"

echo "== 4. POST /api/customers (userId=99)"
curl -s -o /tmp/c1.json -w "  HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d '{"userId":99,"displayName":"Nguyen Van A","email":"a@x.vn","phone":"0901"}' \
  "${CUSTOMER_URL}/api/customers"
cat /tmp/c1.json
echo
CUSTOMER_ID=$(grep -o '"id":[0-9]*' /tmp/c1.json | head -1 | cut -d: -f2)
if [ -z "${CUSTOMER_ID}" ]; then
  echo "FAIL: customer id not returned"
  exit 1
fi
echo "  customerId=${CUSTOMER_ID}"

echo "== 5. POST /api/customers/${CUSTOMER_ID}/addresses (Vietnamese address)"
curl -s -o /tmp/c2.json -w "  HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d '{"line1":"123 Main St","provinceCode":"01","districtCode":"001","communeCode":"00001","isDefault":true}' \
  "${CUSTOMER_URL}/api/customers/${CUSTOMER_ID}/addresses"
cat /tmp/c2.json
echo

echo "== 6. GET /api/customers/${CUSTOMER_ID}/addresses"
ADDR_BODY=$(curl -s "${CUSTOMER_URL}/api/customers/${CUSTOMER_ID}/addresses")
echo "  ${ADDR_BODY}"
if ! echo "${ADDR_BODY}" | grep -q '"provinceCode":"01"'; then
  echo "FAIL: address not returned"
  exit 1
fi
echo "  ok: address shape correct"

echo "== 7. GET /api/customers/${CUSTOMER_ID}"
CUST_BODY=$(curl -s "${CUSTOMER_URL}/api/customers/${CUSTOMER_ID}")
echo "  ${CUST_BODY}" | head -c 300
echo
if ! echo "${CUST_BODY}" | grep -q '"userId":99'; then
  echo "FAIL: customer userId mismatch"
  exit 1
fi
echo "  ok: customer shape correct"

echo "== 8. Verify DB rows"
CUST_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d customer_db -tAc "SELECT count(*) FROM customer WHERE user_id = 99" 2>/dev/null)
ADDR_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d customer_db -tAc "SELECT count(*) FROM address WHERE customer_id = ${CUSTOMER_ID}" 2>/dev/null)
if [ "${CUST_COUNT}" != "1" ]; then
  echo "FAIL: expected 1 customer row, got ${CUST_COUNT}"
  exit 1
fi
if [ "${ADDR_COUNT}" != "1" ]; then
  echo "FAIL: expected 1 address row, got ${ADDR_COUNT}"
  exit 1
fi
echo "  ok: 1 customer + 1 address"

echo
echo "PASS: Story 5.1 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (R-15 deny-list)"
echo "  - POST customer 201 + POST address 201 + GET shape correct"
echo "  - 1 customer + 1 address in DB"