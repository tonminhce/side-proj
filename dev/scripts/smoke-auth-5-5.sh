#!/usr/bin/env bash
# dev/scripts/smoke-auth-5-5.sh — Story 5.5 runtime smoke (FR-74).
#
# Verifies service-account JWT issuance:
#   1. services/auth starts.
#   2. /actuator/health UP on :8089.
#   3. POST /api/auth/service-token — assert 200 + sessionToken non-empty.
#   4. Decode JWT payload — assert serviceAccountId, callerChain, allowedRoles fields.
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
LOG_FILE="${LOG_FILE:-/tmp/auth-smoke-5-5.log}"
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

echo "== 3. POST /api/auth/service-token"
HTTP_CODE=$(curl -s -o /tmp/st.json -w "%{http_code}" \
  -X POST -H "Content-Type: application/json" \
  -d '{"serviceAccountId":"checkout-svc","allowedRoles":["USER","STAFF"],"callerChain":["checkout","payment"]}' \
  "${AUTH_URL}/api/auth/service-token")
if [ "${HTTP_CODE}" != "200" ]; then
  echo "FAIL: service-token returned HTTP ${HTTP_CODE}"
  cat /tmp/st.json
  exit 1
fi
SESSION_TOKEN=$(grep -o '"sessionToken":"[^"]*"' /tmp/st.json | head -1 | cut -d: -f2 | tr -d '"')
if [ -z "${SESSION_TOKEN}" ]; then
  echo "FAIL: sessionToken missing"
  cat /tmp/st.json
  exit 1
fi
echo "  ok: service-token 200"

echo "== 4. Decode JWT payload"
# The middle segment is the base64url-encoded payload. Decode + pretty-print with python3.
PAYLOAD=$(echo "${SESSION_TOKEN}" | cut -d. -f2)
DECODED=$(python3 -c "import base64,sys,json; payload='$PAYLOAD'; payload+='='*(-len(payload)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(payload)), indent=2))" 2>/dev/null || true)
if [ -z "${DECODED}" ]; then
  echo "FAIL: JWT payload decode failed"
  echo "  token=${SESSION_TOKEN:0:60}..."
  exit 1
fi
echo "  decoded payload:"
echo "${DECODED}" | sed 's/^/    /'
if ! echo "${DECODED}" | grep -q '"serviceAccountId": "checkout-svc"'; then
  echo "FAIL: payload missing serviceAccountId"
  exit 1
fi
if ! echo "${DECODED}" | grep -q '"callerChain"'; then
  echo "FAIL: payload missing callerChain"
  exit 1
fi
if ! echo "${DECODED}" | grep -q '"allowedRoles"'; then
  echo "FAIL: payload missing allowedRoles"
  exit 1
fi
echo "  ok: payload has serviceAccountId + callerChain + allowedRoles"

echo
echo "PASS: Story 5.5 smoke end-to-end"
echo "  - /actuator/health UP"
echo "  - POST /api/auth/service-token 200 + sessionToken"
echo "  - JWT payload has serviceAccountId + callerChain + allowedRoles"