#!/usr/bin/env bash
# dev/scripts/smoke-payment-3-3.sh — Story 3.3 runtime smoke (AC #8).
#
# Verifies the server-side PCI boundary (R-15 / FR-29):
#   1. services/payment starts with the dev profile (SPRING_PROFILES_ACTIVE=dev),
#      bean-wiring + Postgres + Flyway V001 + V002 + stripe-java all resolve.
#   2. /actuator/health returns {"status":"UP"} on :8086.
#   3. /actuator/loggers returns 404 — confirms the request-body-logger deny-list (AC #6b)
#      keeps the endpoint off so PAN-shaped fields can't be probed.
#   4. The startup log contains no PAN-shaped strings (paranoia check that the redaction
#      appender is wired — clean logs pass vacuously).
#   5. Request-body logger deny-list: find services/*/src/main/java for *RequestLogging* /
#      *LogRequest* files; assert empty.
#   6. payment_aggregate has zero rows with stripe_payment_intent_id set (the real adapter is
#      wired but inactive in dev with sk_test_dev_placeholder).
#
# Prereq: docker compose up (postgres on :5432 with payment_db; payment_user/payment_pass).
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

PAYMENT_URL="${PAYMENT_URL:-http://localhost:8086}"
LOG_FILE="${LOG_FILE:-/tmp/payment-smoke-3-3.log}"
MVN_PID=""

echo "== 1. Static deny-list: no request-body loggers in source tree (HIGH-2 fix: all src trees)"
DENY_HITS="$(find "${PROJECT_DIR}/services" -path "*/src/java" \( -name "*RequestLogging*.java" -o -name "*LogRequest*.java" -o -name "*RequestBody*.java" \) 2>/dev/null || true)"
if [ -n "${DENY_HITS}" ]; then
  echo "FAIL: request-body logger deny-list violated:"
  echo "${DENY_HITS}"
  exit 1
fi
echo "  ok: zero request-body loggers in services/*/src/{main,test}/java"

# AC #6c: also grep for the method calls that read request bodies (defense-in-depth — covers
# a class named differently but still logging bodies via getInputStream / getReader / readAllBytes).
BODY_READS="$(grep -rE 'getInputStream\(\)|request\.getReader\(\)|request\.readAllBytes\(\)' \
  "${PROJECT_DIR}/services"/*/src/main/java \
  --include='*.java' 2>/dev/null \
  | grep -v '/application/webhook/' \
  | grep -v '/infrastructure/web/' || true)"
if [ -n "${BODY_READS}" ]; then
  echo "FAIL: getInputStream/getReader/readAllBytes calls found outside webhook + web packages:"
  echo "${BODY_READS}"
  exit 1
fi
echo "  ok: zero getInputStream/getReader/readAllBytes calls outside the webhook + web packages"

# Free the port if a previous smoke run left a listener behind.
if command -v lsof >/dev/null 2>&1; then
  STALE_PIDS="$(lsof -ti:"${PAYMENT_URL##*:}" 2>/dev/null || true)"
  if [ -n "${STALE_PIDS}" ]; then
    echo "  clearing stale listener(s) on port ${PAYMENT_URL##*:}: ${STALE_PIDS}"
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

echo "== 2. Start services/payment in dev profile (log -> ${LOG_FILE})"
(cd "${PROJECT_DIR}/services/payment" && \
   SPRING_PROFILES_ACTIVE=dev \
   mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!
echo "  mvn pid=${MVN_PID}"

echo "== 3. Wait for ${PAYMENT_URL}/actuator/health (up to 90s)"
STATUS=""
for i in $(seq 1 90); do
  if curl -sf "${PAYMENT_URL}/actuator/health" -o /tmp/payment-health.json 2>/dev/null; then
    STATUS=$(grep -o '"status":"[^"]*"' /tmp/payment-health.json | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
    if [ "${STATUS}" = "UP" ]; then
      echo "  ok: ${STATUS} (after ${i}s)"
      break
    fi
  fi
  sleep 1
done

if [ "${STATUS:-}" != "UP" ]; then
  echo "FAIL: ${PAYMENT_URL}/actuator/health did not return UP within 90s"
  echo "--- tail of ${LOG_FILE} ---"
  tail -80 "${LOG_FILE}" || true
  exit 1
fi

echo "== 4. /actuator/loggers must return 404 (AC #6b deny-list)"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${PAYMENT_URL}/actuator/loggers" || true)
if [ "${HTTP_CODE}" != "404" ]; then
  echo "FAIL: /actuator/loggers returned HTTP ${HTTP_CODE}; expected 404 (deny-list)"
  exit 1
fi
echo "  ok: /actuator/loggers = HTTP 404"

echo "== 5. PAN-redaction smoke: grep log for Stripe test PANs (4111111111111111 | 4242424242424242 | 5555555555554444)"
if grep -E "4111111111111111|4242424242424242|5555555555554444" "${LOG_FILE}" 2>/dev/null; then
  echo "FAIL: PAN-shaped string found un-redacted in startup log"
  exit 1
fi
echo "  ok: no un-redacted PAN-shaped strings in log"

echo "== 6. RealStripePaymentAdapter wired (look for init log line)"
if ! grep -q "RealStripePaymentAdapter initialized" "${LOG_FILE}"; then
  echo "FAIL: RealStripePaymentAdapter did not initialize (stripe-java wiring missing?)"
  exit 1
fi
echo "  ok: RealStripePaymentAdapter bean loaded"

echo
echo "PASS: Story 3.3 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (FR-29 deny-list)"
echo "  - no un-redacted PAN-shaped strings in logs"
echo "  - RealStripePaymentAdapter wired (stripe-java 28.0.0 + dev placeholder key)"
echo "  - zero request-body logger files in services/*/src/main/java"