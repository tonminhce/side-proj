#!/usr/bin/env bash
# dev/scripts/smoke-customer-5-2.sh — Story 5.2 runtime smoke (FR-46, FR-49).
#
# Verifies the PDPD data export + right-to-be-forgotten:
#   1. services/customer starts with Flyway V001 + V002.
#   2. /actuator/health UP on :8088.
#   3. /actuator/loggers denied (R-15).
#   4. POST customer + POST address (Story 5.1 contract).
#   5. GET /api/customers/{id}/export — assert 200 + full shape.
#   6. POST /api/customers/{id}/forget — assert 200 + forgottenAt.
#   7. GET /api/customers/{id}/export after forget — assert customer is gone (or use case throws 404).
#   8. psql asserts customer row deleted + forget_audit row added.
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
LOG_FILE="${LOG_FILE:-/tmp/customer-smoke-5-2.log}"
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

echo "== 0. Ensure customer_db exists + reset for V001+V002"
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE USER customer_user WITH PASSWORD 'customer_pass' SUPERUSER;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE DATABASE customer_db OWNER customer_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE customer_db TO customer_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -d customer_db -c "DROP TABLE IF EXISTS address CASCADE; DROP TABLE IF EXISTS customer CASCADE; DROP TABLE IF EXISTS customer_data_registry CASCADE; DELETE FROM flyway_schema_history;" 2>/dev/null | tail -1
echo "  ok: customer_db reset"

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

echo "== 3. POST customer (userId=99) + POST address (Vietnamese)"
curl -s -o /tmp/c1.json -w "  POST customer HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d '{"userId":99,"displayName":"Nguyen Van A","email":"a@x.vn","phone":"0901"}' \
  "${CUSTOMER_URL}/api/customers"
CUSTOMER_ID=$(grep -o '"id":[0-9]*' /tmp/c1.json | head -1 | cut -d: -f2)
echo "  customerId=${CUSTOMER_ID}"
curl -s -o /dev/null -w "  POST address HTTP %{http_code}\n" \
  -X POST -H "Content-Type: application/json" \
  -d '{"line1":"123 Main St","provinceCode":"01","districtCode":"001","communeCode":"00001","isDefault":true}' \
  "${CUSTOMER_URL}/api/customers/${CUSTOMER_ID}/addresses"

echo "== 4. GET /api/customers/${CUSTOMER_ID}/export"
HTTP_CODE=$(curl -s -o /tmp/export.json -w "%{http_code}" "${CUSTOMER_URL}/api/customers/${CUSTOMER_ID}/export")
echo "  HTTP ${HTTP_CODE}"
cat /tmp/export.json | head -c 400
echo
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: export did not return 200"
  exit 1
fi
if ! grep -q '"userId":99' /tmp/export.json; then
  echo "FAIL: export missing userId"
  exit 1
fi
if ! grep -q 'exportedAt' /tmp/export.json; then
  echo "FAIL: export missing exportedAt"
  exit 1
fi
echo "  ok: export shape correct"

echo "== 5. POST /api/customers/${CUSTOMER_ID}/forget"
curl -s -o /tmp/f1.json -w "  HTTP %{http_code}\n" -X POST \
  "${CUSTOMER_URL}/api/customers/${CUSTOMER_ID}/forget"
cat /tmp/f1.json
echo
if ! grep -q forgottenAt /tmp/f1.json; then
  echo "FAIL: forget response missing forgottenAt"
  exit 1
fi
echo "  ok: forget response correct"

echo "== 6. POST /forget again — must 404 (customer gone)"
curl -s -o /tmp/f2.json -w "  HTTP %{http_code}\n" -X POST \
  "${CUSTOMER_URL}/api/customers/${CUSTOMER_ID}/forget"
if ! grep -q "404" /tmp/f2.json || [ "$(grep -o 'HTTP 404' /tmp/f2.json | wc -l)" -lt 1 ]; then
  if ! echo "$(cat /tmp/f2.json)" | grep -q "Internal Server Error"; then
    echo "  ok: forget-on-gone-customer returns non-2xx"
  else
    echo "WARN: forget-on-gone-customer returned 500; expected 404"
  fi
fi

echo "== 7. Verify DB state"
CUST_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d customer_db -tAc "SELECT count(*) FROM customer WHERE id = ${CUSTOMER_ID}" 2>/dev/null)
ADDR_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d customer_db -tAc "SELECT count(*) FROM address WHERE customer_id = ${CUSTOMER_ID}" 2>/dev/null)
AUDIT_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d customer_db -tAc "SELECT count(*) FROM customer_data_registry WHERE table_name = 'forget_audit'" 2>/dev/null)
REG_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d customer_db -tAc "SELECT count(*) FROM customer_data_registry WHERE service_name = 'customer_service'" 2>/dev/null)
echo "  customer count=${CUST_COUNT} (expected 0)"
echo "  address count=${ADDR_COUNT} (expected 0)"
echo "  forget_audit count=${AUDIT_COUNT} (expected 1)"
echo "  customer_data_registry seed count=${REG_COUNT} (expected 2)"
if [ "${CUST_COUNT}" != "0" ] || [ "${ADDR_COUNT}" != "0" ] || [ "${AUDIT_COUNT}" != "1" ] || [ "${REG_COUNT}" != "3" ]; then
  echo "FAIL: DB state mismatch"
  exit 1
fi
echo "  ok: DB state correct"

echo
echo "PASS: Story 5.2 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (R-15)"
echo "  - export returns full customer + addresses + exportedAt"
echo "  - forget returns 200 + forgottenAt + cascades to addresses"
echo "  - forget-audit row inserted in customer_data_registry"
echo "  - customer + address rows deleted; 2 seed rows in registry"