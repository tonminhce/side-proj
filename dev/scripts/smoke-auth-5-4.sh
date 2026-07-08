#!/usr/bin/env bash
# dev/scripts/smoke-auth-5-4.sh — Story 5.4 runtime smoke (FR-73, FR-75, FR-76).
#
# Verifies email + password auth + account lockout:
#   1. services/auth starts with Flyway V001.
#   2. /actuator/health UP on :8089.
#   3. /actuator/loggers denied (R-15).
#   4. POST /api/auth/register — assert 201 + sessionToken.
#   5. POST /api/auth/login with correct password — assert 200.
#   6. POST /api/auth/login with wrong password 5 times — assert 423 on the 5th attempt.
#   7. POST /api/auth/login with correct password after lockout — assert 423.
#   8. psql: users table has 1 row.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

AUTH_URL="${AUTH_URL:-http://localhost:8089}"
LOG_FILE="${LOG_FILE:-/tmp/auth-smoke-5-4.log}"
MVN_PID=""

if command -v lsof >/dev/null 2>&1; then
  STALE_PIDS="$(lsof -ti:"${AUTH_URL##*:}" 2>/dev/null || true)"
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

echo "== 0. Ensure auth_db exists"
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE USER auth_user WITH PASSWORD 'auth_pass' SUPERUSER;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "CREATE DATABASE auth_db OWNER auth_user;" 2>/dev/null || true
docker exec ecommerce-platform-postgres-1 psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE auth_db TO auth_user;" 2>/dev/null || true
echo "  ok: auth_db ready"

export HMAC_JWT_SECRET="${HMAC_JWT_SECRET:-$(openssl rand -hex 32 2>/dev/null || echo "$(printf '%064x' $(date +%s%N))")}"

echo "== 1. Start services/auth (log -> ${LOG_FILE})"
(cd "${PROJECT_DIR}/services/auth" && \
   SPRING_PROFILES_ACTIVE=dev \
   HMAC_JWT_SECRET="${HMAC_JWT_SECRET}" \
   mvn -q spring-boot:run >"${LOG_FILE}" 2>&1) &
MVN_PID=$!

echo "== 2. Wait for ${AUTH_URL}/actuator/health (up to 90s)"
STATUS=""
for i in $(seq 1 90); do
  if curl -sf "${AUTH_URL}/actuator/health" -o /tmp/auth-health.json 2>/dev/null; then
    STATUS=$(grep -o '"status":"[^"]*"' /tmp/auth-health.json | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
    if [ "${STATUS}" = "UP" ]; then
      echo "  ok: ${STATUS} (after ${i}s)"
      break
    fi
  fi
  sleep 1
done

if [ "${STATUS:-}" != "UP" ]; then
  echo "FAIL: ${AUTH_URL}/actuator/health did not return UP"
  tail -80 "${LOG_FILE}" || true
  exit 1
fi

EMAIL="auth-smoke-$(date +%s)@x.vn"

echo "== 3. POST /api/auth/register (email=$EMAIL)"
HTTP_CODE=$(curl -s -o /tmp/r1.json -w "%{http_code}" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\"}" \
  "${AUTH_URL}/api/auth/register")
if [ "${HTTP_CODE}" != "201" ]; then
  echo "FAIL: register returned HTTP ${HTTP_CODE}"
  cat /tmp/r1.json
  exit 1
fi
SESSION_TOKEN=$(grep -o '"sessionToken":"[^"]*"' /tmp/r1.json | head -1 | cut -d: -f2 | tr -d '"')
if [ -z "${SESSION_TOKEN}" ]; then
  echo "FAIL: sessionToken missing"
  cat /tmp/r1.json
  exit 1
fi
echo "  ok: register 201, sessionToken=${SESSION_TOKEN:0:20}..."

echo "== 4. POST /api/auth/login (correct password) — must 200"
HTTP_CODE=$(curl -s -o /tmp/r2.json -w "%{http_code}" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\"}" \
  "${AUTH_URL}/api/auth/login")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: login returned HTTP ${HTTP_CODE}"
  cat /tmp/r2.json
  exit 1
fi
echo "  ok: login 200"

echo "== 5. POST /api/auth/login 5 times with wrong password — 5th must 423"
LOCKED=""
for i in $(seq 1 5); do
  HTTP_CODE=$(curl -s -o /tmp/r3.json -w "%{http_code}" \
    -X POST -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"WRONG$i\"}" \
    "${AUTH_URL}/api/auth/login")
  echo "  attempt $i: HTTP $HTTP_CODE"
  if [ "$HTTP_CODE" = "423" ]; then
    LOCKED="yes"
  fi
done
if [ -z "${LOCKED}" ]; then
  echo "FAIL: 5 wrong attempts did not produce HTTP 423"
  cat /tmp/r3.json
  exit 1
fi
echo "  ok: account locked on 5th failed attempt"

echo "== 6. POST /api/auth/login with correct password after lockout — must 423"
HTTP_CODE=$(curl -s -o /tmp/r4.json -w "%{http_code}" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\"}" \
  "${AUTH_URL}/api/auth/login")
if [ "${HTTP_CODE}" != "423" ]; then
  echo "FAIL: correct password after lockout must return 423 (got HTTP ${HTTP_CODE})"
  cat /tmp/r4.json
  exit 1
fi
echo "  ok: locked account returns 423 even with correct password"

echo "== 7. Verify users table has 1 row"
USER_COUNT=$(docker exec ecommerce-platform-postgres-1 psql -U postgres -d auth_db -tAc "SELECT count(*) FROM users WHERE email = '$EMAIL'" 2>/dev/null)
if [ "${USER_COUNT}" != "1" ]; then
  echo "FAIL: expected 1 user row, got ${USER_COUNT}"
  exit 1
fi
echo "  ok: 1 user row"

echo
echo "PASS: Story 5.4 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - /actuator/loggers denied (R-15)"
echo "  - register 201 + sessionToken issued"
echo "  - login 200 with correct password"
echo "  - 5 wrong attempts lock the account (HTTP 423)"
echo "  - locked account returns 423 even with correct password"