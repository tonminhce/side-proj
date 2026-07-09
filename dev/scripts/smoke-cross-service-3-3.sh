#!/usr/bin/env bash
# dev/scripts/smoke-cross-service-3-3.sh — Story 3.3 + Epic 4 MEDIUM sweep (LOW-2 deferred).
#
# Verifies the R-15 "across all services" contract by booting each service that ships a
# logback-spring.xml (post the Epic 4 MEDIUM sweep) and asserting /actuator/health = UP.
# Each service boots in the dev profile, hits health, then is killed. Run AFTER docker
# compose up (Postgres + Kafka + Redis on the standard ports).
#
# Prereq: dev/scripts/smoke-payment-3-3.sh has been run at least once to validate the
# logback-include.xml + PanRedactingAppender wiring on payment.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

# Map: service-name -> port. Services that own a logback-spring.xml + an /actuator/health
# endpoint that the gateway + BFF rely on. Order is significant: cheaper services first.
# Each entry must be unique. Add new services as their logback-spring.xml + health endpoint
# ship; remove entries that drop the contract.
SERVICES=(
  "catalog:8081"
  "inventory:8083"
  "cart:8085"
  "checkout:8084"
)

echo "== Cross-service smoke (R-15 'across all services' contract)"
echo "   Services: ${SERVICES[*]}"

PASSED=0
FAILED=0
FAILED_SERVICES=()

cleanup_mvn() {
  if [ -n "${MVN_PID:-}" ] && kill -0 "${MVN_PID}" 2>/dev/null; then
    kill "${MVN_PID}" 2>/dev/null || true
    wait "${MVN_PID}" 2>/dev/null || true
  fi
  MVN_PID=""
}
trap cleanup_mvn EXIT

for entry in "${SERVICES[@]}"; do
  svc="${entry%%:*}"
  port="${entry##*:}"
  url="http://localhost:${port}"
  log="/tmp/${svc}-cross-smoke-3-3.log"
  echo ""
  echo "-- ${svc} (${url})"

  if ! [ -f "${PROJECT_DIR}/services/${svc}/pom.xml" ]; then
    echo "   SKIP: services/${svc}/pom.xml does not exist"
    continue
  fi
  if ! [ -f "${PROJECT_DIR}/services/${svc}/src/main/resources/logback-spring.xml" ]; then
    echo "   FAIL: services/${svc}/src/main/resources/logback-spring.xml missing"
    FAILED=$((FAILED + 1))
    FAILED_SERVICES+=("${svc}")
    continue
  fi

  # Free the port if a previous run left a listener behind.
  if command -v lsof >/dev/null 2>&1; then
    STALE_PIDS="$(lsof -ti:"${port}" 2>/dev/null || true)"
    if [ -n "${STALE_PIDS}" ]; then
      echo "   clearing stale listener(s) on port ${port}: ${STALE_PIDS}"
      # shellcheck disable=SC2086
      kill -9 ${STALE_PIDS} 2>/dev/null || true
      sleep 1
    fi
  fi

  MVN_PID=""
  (cd "${PROJECT_DIR}/services/${svc}" && \
     SPRING_PROFILES_ACTIVE=dev \
     mvn -q spring-boot:run >"${log}" 2>&1) &
  MVN_PID=$!

  HEALTH="DOWN"
  for i in $(seq 1 90); do
    if curl -sf "${url}/actuator/health" -o /tmp/${svc}-health.json 2>/dev/null; then
      HEALTH=$(grep -o '"status":"[^"]*"' /tmp/${svc}-health.json | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
      if [ "${HEALTH}" = "UP" ]; then
        break
      fi
    fi
    sleep 1
  done

  # Kill before checking — this is a smoke, not a long-running boot.
  cleanup_mvn

  if [ "${HEALTH}" != "UP" ]; then
    echo "   FAIL: /actuator/health did not return UP within 90s (last status: ${HEALTH})"
    echo "   --- tail of ${log} ---"
    tail -40 "${log}" 2>/dev/null || true
    FAILED=$((FAILED + 1))
    FAILED_SERVICES+=("${svc}")
    continue
  fi

  # Confirm PanRedactingAppender was loaded: the startup log must include either
  # logback-include.xml's appender class or a "REDACTING_CONSOLE" appender name.
  if ! grep -qE "PanRedactingAppender|REDACTING_CONSOLE" "${log}" 2>/dev/null; then
    echo "   WARN: PanRedactingAppender not in startup log (the redactor may not be wired)"
  fi

  echo "   ok: /actuator/health = UP"
  PASSED=$((PASSED + 1))
done

echo ""
echo "== Result: ${PASSED} passed, ${FAILED} failed"
if [ "${FAILED}" -gt 0 ]; then
  echo "   Failed services: ${FAILED_SERVICES[*]}"
  exit 1
fi
echo "PASS: Cross-service R-15 contract (Story 3.3 + Epic 4 MEDIUM sweep)"
