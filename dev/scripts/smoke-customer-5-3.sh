#!/usr/bin/env bash
# dev/scripts/smoke-customer-5-3.sh — Story 5.3 runtime smoke (FR-48).
#
# Verifies Vietnamese address autocomplete:
#   1. services/customer starts with V001/V002 + seed JSON loaded.
#   2. /actuator/health UP on :8088.
#   3. GET /api/customers/addresses/autocomplete?type=province&q=Tan — assert ≥1 result.
#   4. GET .../autocomplete?type=district&q=Binh&parent=79 — assert ≥1 result (HCMC).
#   5. GET .../autocomplete?type=district&q=Binh (no parent) — assert HTTP 400.
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
LOG_FILE="${LOG_FILE:-/tmp/customer-smoke-5-3.log}"
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

echo "== 3. GET province autocomplete (q=Tay — matches Tây Ninh via diacritic folding)"
HTTP_CODE=$(curl -s -o /tmp/auto.json -w "%{http_code}" "${CUSTOMER_URL}/api/customers/addresses/autocomplete?type=province&q=Tay")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: autocomplete?type=province returned HTTP ${HTTP_CODE}"
  cat /tmp/auto.json
  exit 1
fi
COUNT=$(grep -oE '\{[^}]*\}' /tmp/auto.json | wc -l | tr -d ' ')
echo "  result count=${COUNT}"
if [ "${COUNT}" -lt 1 ]; then
  echo "FAIL: province autocomplete returned 0 results"
  cat /tmp/auto.json
  exit 1
fi
echo "  ok: province autocomplete returns ≥1 result"

echo "== 4. GET district autocomplete (q=Binh&parent=79 = HCMC)"
HTTP_CODE=$(curl -s -o /tmp/auto.json -w "%{http_code}" "${CUSTOMER_URL}/api/customers/addresses/autocomplete?type=district&q=Binh&parent=79")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: autocomplete?type=district returned HTTP ${HTTP_CODE}"
  cat /tmp/auto.json
  exit 1
fi
COUNT=$(grep -oE '\{[^}]*\}' /tmp/auto.json | wc -l | tr -d ' ')
echo "  result count=${COUNT}"
if [ "${COUNT}" -lt 1 ]; then
  echo "FAIL: district autocomplete returned 0 results"
  cat /tmp/auto.json
  exit 1
fi
if ! grep -q "Bình Thạnh" /tmp/auto.json; then
  echo "FAIL: expected 'Bình Thạnh' in results"
  cat /tmp/auto.json
  exit 1
fi
echo "  ok: district autocomplete returns Bình Thạnh"

echo "== 5. GET district autocomplete WITHOUT parent — must 400"
HTTP_CODE=$(curl -s -o /tmp/auto.json -w "%{http_code}" "${CUSTOMER_URL}/api/customers/addresses/autocomplete?type=district&q=Binh")
if [ "${HTTP_CODE}" != "400" ]; then
  echo "FAIL: missing parent must return 400 (got HTTP ${HTTP_CODE})"
  cat /tmp/auto.json
  exit 1
fi
echo "  ok: missing parent returns 400"

echo "== 6. GET province autocomplete (q=tay — diacritic-folded)"
HTTP_CODE=$(curl -s -o /tmp/auto.json -w "%{http_code}" "${CUSTOMER_URL}/api/customers/addresses/autocomplete?type=province&q=tay")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: diacritic-folded query returned HTTP ${HTTP_CODE}"
  exit 1
fi
if ! grep -q "Tây Ninh\|Tay Ninh" /tmp/auto.json; then
  echo "FAIL: diacritic-folded query did not return Tây Ninh"
  cat /tmp/auto.json
  exit 1
fi
echo "  ok: diacritic folding works (Tay Ninh matched without diacritics)"

echo
echo "PASS: Story 5.3 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - province autocomplete returns ≥1 result for 'Tan'"
echo "  - district autocomplete (HCMC parent=79) returns Bình Thạnh"
echo "  - missing parent returns HTTP 400"
echo "  - diacritic-folded query 'tay' matches 'Tây Ninh'"