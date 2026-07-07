#!/usr/bin/env bash
# dev/scripts/smoke-payment-3-2.sh — Story 3.2 runtime smoke (AC #10).
#
# Verifies end-to-end webhook dedup:
#   1. services/payment starts (bean-wiring + Postgres + Flyway V001 + V002).
#   2. /actuator/health returns {"status":"UP"} on :8086.
#   3. POST /webhooks/stripe with a stable event.id returns {"dedup":false} on first delivery.
#   4. Replaying the SAME curl (same event.id byte-for-byte) returns {"dedup":true}.
#   5. SELECT count(*) FROM webhook_dedup WHERE event_id = '<id>' returns 1 (not 2).
#   6. Flyway log shows V002 applied.
#
# The runtime smoke is non-negotiable per project memory (runtime-smoke-rule.md) — F1 review
# caught a bean-name clash unit tests missed; the boot path is the only thing that catches it.
# Unit tests + IT alone do not prove the controller + use case + port + JPA write path +
# Postgres UNIQUE constraint + dedup flow work together.
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
WEBHOOK_URL="${PAYMENT_URL}/webhooks/stripe"
LOG_FILE="${LOG_FILE:-/tmp/payment-smoke-3-2.log}"
MVN_PID=""

EVENT_ID="evt_smoke_$$_$(date +%s)"
echo "  using event.id = ${EVENT_ID}"

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

echo "== 1. Start services/payment in background (log -> ${LOG_FILE})"
(cd "${PROJECT_DIR}/services/payment" && mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!
echo "  mvn pid=${MVN_PID}"

echo "== 2. Wait for ${PAYMENT_URL}/actuator/health (up to 90s)"
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

echo "== 3. Flyway V002 applied (webhook_dedup + webhook_delivery_log tables)"
# ponytail: tighter regex than the canonical "Successfully applied N migration" — that one would
# false-positive if V001 succeeded but V002 failed. We require either the V002 migrate line, the
# v002 final-state line, or the "up to date" catch-up. psql belt-and-braces below confirms in the
# happy path.
if ! grep -qE '"002 - create_webhook_dedup"|now at version v002|up to date\. No migration necessary' "${LOG_FILE}"; then
  echo "FAIL: Flyway did not apply V002 — see ${LOG_FILE}"
  tail -40 "${LOG_FILE}" || true
  exit 1
fi
# Belt-and-braces: SELECT count(*) on flyway_schema_history for the V002 row (works whether V002
# was applied this run or a previous one). Skip if psql isn't installed (dev container has it;
# mac/linux devs may not).
if command -v psql >/dev/null 2>&1; then
  PGPASSWORD="${POSTGRES_PAYMENT_PASSWORD:-payment_pass}" \
    psql -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
         -U "${POSTGRES_PAYMENT_USER:-payment_user}" \
         -d "${POSTGRES_PAYMENT_DB:-payment_db}" \
         -At -c "SELECT count(*) FROM flyway_schema_history WHERE version = '002'" > /tmp/payment-fw-history.txt
  FW_COUNT="$(cat /tmp/payment-fw-history.txt | tr -d '[:space:]')"
  if [ "${FW_COUNT}" != "1" ]; then
    echo "FAIL: flyway_schema_history has ${FW_COUNT} V002 rows, expected 1"
    exit 1
  fi
fi
echo "  ok: V002 applied (or up to date)"

echo "== 4. POST /webhooks/stripe (first delivery — expect dedup:false)"
PAYLOAD="{\"id\":\"${EVENT_ID}\",\"type\":\"payment_intent.succeeded\",\"livemode\":false,\"data\":{},\"created\":$(date +%s)}"
FIRST_BODY="$(curl -s -X POST -H 'Content-Type: application/json' -d "${PAYLOAD}" "${WEBHOOK_URL}")"
echo "  response: ${FIRST_BODY}"
if ! printf '%s' "${FIRST_BODY}" | grep -q '"dedup":false'; then
  echo "FAIL: first delivery did not return dedup:false"
  exit 1
fi
if ! printf '%s' "${FIRST_BODY}" | grep -q "\"eventId\":\"${EVENT_ID}\""; then
  echo "FAIL: first delivery eventId mismatch"
  exit 1
fi
echo "  ok: dedup:false on first delivery"

echo "== 5. POST /webhooks/stripe (replay same payload — expect dedup:true)"
SECOND_BODY="$(curl -s -X POST -H 'Content-Type: application/json' -d "${PAYLOAD}" "${WEBHOOK_URL}")"
echo "  response: ${SECOND_BODY}"
if ! printf '%s' "${SECOND_BODY}" | grep -q '"dedup":true'; then
  echo "FAIL: replay did not return dedup:true"
  exit 1
fi
echo "  ok: dedup:true on replay"

if ! command -v psql >/dev/null 2>&1; then
  echo "WARN: psql not found; install postgres-client to verify the count(*). Skipping SQL check."
else
  echo "== 6. SELECT count(*) FROM webhook_dedup WHERE event_id = '${EVENT_ID}' (expect 1)"
  PGPASSWORD="${POSTGRES_PAYMENT_PASSWORD:-payment_pass}" \
    psql -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
         -U "${POSTGRES_PAYMENT_USER:-payment_user}" \
         -d "${POSTGRES_PAYMENT_DB:-payment_db}" \
         -At -c "SELECT count(*) FROM webhook_dedup WHERE event_id = '${EVENT_ID}'" > /tmp/payment-dedup-count.txt
  COUNT="$(cat /tmp/payment-dedup-count.txt | tr -d '[:space:]')"
  echo "  count: ${COUNT}"
  if [ "${COUNT}" != "1" ]; then
    echo "FAIL: webhook_dedup row count for ${EVENT_ID} is ${COUNT}, expected 1"
    exit 1
  fi
  echo "  ok: exactly one dedup row"
fi

echo "All payment smoke checks (Story 3.2) passed."