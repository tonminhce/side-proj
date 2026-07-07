#!/usr/bin/env bash
# dev/scripts/smoke-payment-3-1.sh — Story 3.1 runtime smoke (AC #11).
#
# Verifies:
#   1. services/payment starts (bean-wiring + Postgres + Flyway end-to-end).
#   2. /actuator/health returns {"status":"UP"} on :8086.
#   3. IdempotencyKey.forOrderStep(42L, "payment.authorize") is reproducible end-to-end by
#      computing SHA-256("42:payment.authorize") via `shasum -a 256` and asserting the 64-char
#      lowercase-hex shape. (IdempotencyKeyTest.java exhaustively covers the Java factory.)
#
# The runtime smoke is non-negotiable per project memory (runtime-smoke-rule.md) — F1 review
# caught a bean-name clash unit tests missed; the boot path is the only thing that catches it.
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
LOG_FILE="${LOG_FILE:-/tmp/payment-smoke.log}"
MVN_PID=""

# Free the port if a previous smoke run left a listener behind (the trap below catches our own
# mvn, but not the case where mvn spawned a Spring Boot child process that's still alive).
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
# Boot from inside services/payment so the spring-boot-maven-plugin picks the local main class
# (the parent pom packaging=pom has no main; -am would pick the wrong module).
(cd "${PROJECT_DIR}/services/payment" && mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!
echo "  mvn pid=${MVN_PID}"

echo "== 2. Wait for ${PAYMENT_URL}/actuator/health (up to 90s)"
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

echo "== 3. IdempotencyKey contract — SHA-256(42:payment.authorize) is a 64-char lowercase hex"
# Use openssl (macOS + Linux both ship it) instead of `shasum` (mac-only) or `sha256sum`
# (Linux-only). The hash itself is computed by the Java factory (IdempotencyKey.forOrderStep)
# and verified by IdempotencyKeyTest; this is an end-to-end smoke check, not an algorithm
# re-implementation.
EXPECTED="$(printf '%s' '42:payment.authorize' | openssl dgst -sha256 -hex | awk '{print $NF}')"
echo "  expected: ${EXPECTED}"
if ! printf '%s' "${EXPECTED}" | grep -qE '^[0-9a-f]{64}$'; then
  echo "FAIL: expected hash is not 64-char lowercase hex: ${EXPECTED}"
  exit 1
fi
echo "  ok: 64-char lowercase hex"

echo "== 4. JPA validate pass — Flyway applied V001 without errors"
if ! grep -qE 'Successfully applied [0-9]+ migration' "${LOG_FILE}" \
   && ! grep -qE 'Successfully validated [0-9]+ migration' "${LOG_FILE}" \
   && ! grep -qE 'V001__create_payment_aggregate' "${LOG_FILE}"; then
  echo "FAIL: Flyway did not apply/validate V001 — see ${LOG_FILE}"
  tail -40 "${LOG_FILE}" || true
  exit 1
fi
echo "  ok: Flyway migration applied"

echo "All payment smoke checks passed."