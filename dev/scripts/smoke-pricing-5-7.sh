#!/usr/bin/env bash
# dev/scripts/smoke-pricing-5-7.sh — Story 5.7 runtime smoke (FR-65, FR-67).
#
# Verifies the PricingService stub:
#   1. services/pricing starts with the static pricebook.
#   2. /actuator/health UP on :8090.
#   3. /actuator/loggers denied (R-15).
#   4. GET /api/pricing/variant-1 — assert 200 + listPriceCents + currency:VND.
#   5. GET /api/pricing/variant-unknown — assert 404.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

PRICING_URL="${PRICING_URL:-http://localhost:8090}"
LOG_FILE="${LOG_FILE:-/tmp/pricing-smoke-5-7.log}"
MVN_PID=""

if command -v lsof >/dev/null 2>&1; then
  STALE_PIDS="$(lsof -ti:"${PRICING_URL##*:}" 2>/dev/null || true)"
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

echo "== 1. Start services/pricing (log -> ${LOG_FILE})"
(cd "${PROJECT_DIR}/services/pricing" && \
   SPRING_PROFILES_ACTIVE=dev \
   mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!

echo "== 2. Wait for ${PRICING_URL}/actuator/health (up to 90s)"
STATUS=""
for i in $(seq 1 90); do
  if curl -sf "${PRICING_URL}/actuator/health" -o /tmp/pricing-health.json 2>/dev/null; then
    STATUS=$(grep -o '"status":"[^"]*"' /tmp/pricing-health.json | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
    if [ "${STATUS}" = "UP" ]; then
      echo "  ok: ${STATUS} (after ${i}s)"
      break
    fi
  fi
  sleep 1
done

if [ "${STATUS:-}" != "UP" ]; then
  echo "FAIL: ${PRICING_URL}/actuator/health did not return UP"
  tail -80 "${LOG_FILE}" || true
  exit 1
fi

echo "== 3. /actuator/loggers must return 404 (R-15 deny-list)"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${PRICING_URL}/actuator/loggers" || true)
if [ "${HTTP_CODE}" != "404" ]; then
  echo "FAIL: /actuator/loggers returned HTTP ${HTTP_CODE}; expected 404"
  exit 1
fi
echo "  ok: /actuator/loggers = HTTP 404"

echo "== 4. GET /api/pricing/variant-1"
HTTP_CODE=$(curl -s -o /tmp/p1.json -w "%{http_code}" "${PRICING_URL}/api/pricing/variant-1")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: variant-1 returned HTTP ${HTTP_CODE}"
  cat /tmp/p1.json
  exit 1
fi
if ! grep -q '"listPriceCents":1990000' /tmp/p1.json; then
  echo "FAIL: variant-1 listPriceCents mismatch"
  cat /tmp/p1.json
  exit 1
fi
if ! grep -q '"currency":"VND"' /tmp/p1.json; then
  echo "FAIL: variant-1 currency not VND"
  cat /tmp/p1.json
  exit 1
fi
echo "  ok: variant-1 returned 200 with listPriceCents=1990000 + currency=VND"

echo "== 5. GET /api/pricing/variant-unknown — must 404"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${PRICING_URL}/api/pricing/variant-unknown" || true)
if [ "${HTTP_CODE}" != "404" ]; then
  echo "FAIL: unknown variant returned HTTP ${HTTP_CODE}; expected 404"
  exit 1
fi
echo "  ok: unknown variant returns 404"

echo
echo "PASS: Story 5.7 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (R-15)"
echo "  - GET variant-1 returns 200 with listPriceCents=1990000 + currency=VND"
echo "  - GET variant-unknown returns 404"