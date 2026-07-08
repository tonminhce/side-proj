#!/usr/bin/env bash
# dev/scripts/smoke-gateway-3-4.sh — Story 3.4 runtime smoke (AC #6).
#
# Verifies the card-testing defense (R-05 / FR-81):
#   1. services/gateway starts with bean-wiring + Redis connection.
#   2. /actuator/health returns {"status":"UP"} on :8080.
#   3. /api/payment/test with X-Real-IP + X-Card-Bin + X-Card-Last4 returns 429 after
#      binVelocityMaxAttempts (10) requests — proves BIN velocity check works end-to-end.
#   4. The 429 response includes RateLimit-* headers (RFC draft-ietf-httpapi-ratelimit-headers).
#   5. Redis stores the rl:bin:<bin>:<window> sorted set (verified via docker exec).
#
# Prereq: docker compose up (Redis on :6379; payment service on :8086 if the route forwards).
#         services/payment does NOT need to be running for this smoke — the rate-limiter fires
#         before the route is invoked; a 429 short-circuits the chain.
# Kills the mvn process on exit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
LOG_FILE="${LOG_FILE:-/tmp/gateway-smoke-3-4.log}"
MVN_PID=""

# Free the port if a previous smoke run left a listener behind.
if command -v lsof >/dev/null 2>&1; then
  STALE_PIDS="$(lsof -ti:"${GATEWAY_URL##*:}" 2>/dev/null || true)"
  if [ -n "${STALE_PIDS}" ]; then
    echo "  clearing stale listener(s) on port ${GATEWAY_URL##*:}: ${STALE_PIDS}"
    # shellcheck disable=SC2086
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

echo "== 1. Verify Redis is UP (prereq)"
if ! docker exec ecommerce-platform-redis-1 redis-cli PING 2>/dev/null | grep -q PONG; then
  echo "FAIL: Redis not responding. Run 'docker compose up -d redis' first."
  exit 1
fi
echo "  ok: Redis PING -> PONG"

echo "== 2. Start services/gateway in background (log -> ${LOG_FILE})"
(cd "${PROJECT_DIR}/services/gateway" && mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!
echo "  mvn pid=${MVN_PID}"

echo "== 3. Wait for ${GATEWAY_URL}/actuator/health (up to 90s)"
STATUS=""
for i in $(seq 1 90); do
  if curl -sf "${GATEWAY_URL}/actuator/health" -o /tmp/gateway-health.json 2>/dev/null; then
    STATUS=$(grep -o '"status":"[^"]*"' /tmp/gateway-health.json | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
    if [ "${STATUS}" = "UP" ]; then
      echo "  ok: ${STATUS} (after ${i}s)"
      break
    fi
  fi
  sleep 1
done

if [ "${STATUS:-}" != "UP" ]; then
  echo "FAIL: ${GATEWAY_URL}/actuator/health did not return UP within 90s"
  echo "--- tail of ${LOG_FILE} ---"
  tail -80 "${LOG_FILE}" || true
  exit 1
fi

echo "== 4. /actuator/loggers must return 404 (R-15 deny-list baseline)"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${GATEWAY_URL}/actuator/loggers" || true)
if [ "${HTTP_CODE}" != "404" ]; then
  echo "FAIL: /actuator/loggers returned HTTP ${HTTP_CODE}; expected 404 (deny-list)"
  exit 1
fi
echo "  ok: /actuator/loggers = HTTP 404"

echo "== 5. BIN velocity: 11 requests with same BIN, 11th must 429 with RateLimit headers"
# Use a unique 6-digit BIN per smoke run so the prior rl:bin:<bin>:<window> set is empty.
SMOKE_BIN="$(printf '%06d' $(($(date +%s) % 1000000)))"
echo "  using BIN=${SMOKE_BIN}"
for i in $(seq 1 11); do
  RESP=$(curl -s -o /tmp/gateway-resp.txt -w "HTTP %{http_code} rate-limit-remaining=%{header_json}" \
    -H "X-Real-IP: 1.2.3.4" \
    -H "X-Card-Bin: ${SMOKE_BIN}" \
    -H "X-Card-Last4: 4242" \
    "${GATEWAY_URL}/api/payment/test" 2>&1) || true
  if [ $i -eq 11 ]; then
    LAST_BODY=$(cat /tmp/gateway-resp.txt 2>/dev/null)
    echo "  request $i: $(echo "$RESP" | grep -o 'HTTP [0-9]*')"
    if ! echo "$RESP" | grep -q "HTTP 429"; then
      echo "FAIL: 11th request did not return HTTP 429 (BIN velocity not enforced)"
      echo "  body: ${LAST_BODY}"
      exit 1
    fi
    if ! echo "$RESP" | grep -qi "ratelimit-limit\|ratelimit-remaining"; then
      echo "FAIL: 11th response missing RateLimit-* headers"
      echo "  body: ${LAST_BODY}"
      exit 1
    fi
  fi
done
echo "  ok: BIN velocity triggered 429 on 11th request"

echo "== 6. Verify Redis sorted set for the test BIN exists"
KEY_COUNT=$(docker exec ecommerce-platform-redis-1 redis-cli --scan --pattern "rl:bin:${SMOKE_BIN}:*" 2>/dev/null | wc -l)
if [ "${KEY_COUNT}" -lt 1 ]; then
  echo "FAIL: no rl:bin:${SMOKE_BIN}:* keys in Redis"
  exit 1
fi
echo "  ok: ${KEY_COUNT} rl:bin:${SMOKE_BIN}:* keys exist"

echo
echo "PASS: Story 3.4 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied"
echo "  - BIN velocity triggered HTTP 429 on 11th request"
echo "  - RateLimit-* headers present on 429 response"
echo "  - Redis rl:bin:<bin>:<window> sorted set populated"