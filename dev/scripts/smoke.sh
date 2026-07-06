#!/usr/bin/env bash
# dev/scripts/smoke.sh — Story 0.3
# Verifies each platform service is responding on its host port.
# Exit 0 if all 7 services respond, exit 1 otherwise.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${PROJECT_DIR}/dev/docker-compose.yml"

# Load dev/.env if present (gitignored) so env-var checks use real values.
if [ -f "${PROJECT_DIR}/dev/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${PROJECT_DIR}/dev/.env"
  set +a
fi

dc() { docker compose -f "${COMPOSE_FILE}" "$@"; }

pass=0
fail=0

report() {
  local rc=$1 label=$2
  if [ "$rc" -eq 0 ]; then
    printf '✓ %s\n' "$label"
    pass=$((pass + 1))
  else
    printf '✗ %s\n' "$label" >&2
    fail=$((fail + 1))
  fi
}

# 1. Postgres (AC #12: SELECT 1 — proves the query engine is up, not just the port)
if dc exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-app}" -tAc "SELECT 1" 2>/dev/null | grep -q '^1$'; then
  report 0 "postgres: SELECT 1 (${POSTGRES_DB:-app})"
else
  report 1 "postgres: SELECT 1 (${POSTGRES_DB:-app})"
fi

# 1b. Postgres per-service DBs (Story 1.1: catalog_db). One Postgres pass per existing service DB —
#     each per-service check verifies the role can connect and run a trivial query.
if dc exec -T postgres psql -U "${POSTGRES_CATALOG_USER:-catalog_user}" -d "${POSTGRES_CATALOG_DB:-catalog_db}" -tAc "SELECT 1" 2>/dev/null | grep -q '^1$'; then
  report 0 "postgres: SELECT 1 (${POSTGRES_CATALOG_DB:-catalog_db})"
else
  report 1 "postgres: SELECT 1 (${POSTGRES_CATALOG_DB:-catalog_db})"
fi

# 2. Kafka
if dc exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
  report 0 "kafka: kafka-topics --list"
else
  report 1 "kafka: kafka-topics --list"
fi

# 3. Elasticsearch
if curl -sf http://localhost:9200/_cluster/health >/dev/null 2>&1; then
  report 0 "elasticsearch: GET /_cluster/health"
else
  report 1 "elasticsearch: GET /_cluster/health"
fi

# 4. Redis
if dc exec -T redis redis-cli ping >/dev/null 2>&1; then
  report 0 "redis: PING"
else
  report 1 "redis: PING"
fi

# 5. Apicurio
if curl -sf http://localhost:8081/apis/registry/v2/groups >/dev/null 2>&1; then
  report 0 "apicurio: GET /apis/registry/v2/groups"
else
  report 1 "apicurio: GET /apis/registry/v2/groups"
fi

# 6. MinIO
if curl -sf http://localhost:9000/minio/health/live >/dev/null 2>&1; then
  report 0 "minio: GET /minio/health/live"
else
  report 1 "minio: GET /minio/health/live"
fi

# 7. OPA
if curl -sf http://localhost:8181/health >/dev/null 2>&1; then
  report 0 "opa: GET /health"
else
  report 1 "opa: GET /health"
fi

# 8. Story 1.4: admin read view boots (manual OTel + browser-check).
#    Skipped here — the BFF and catalog service are application services that ship with their
#    Epic stories; smoke.sh only checks platform infra (Postgres + Kafka + ES + Redis + Apicurio
#    + MinIO + OPA). Verify the admin path manually with the curl in dev/README.md "Read admin view".

total=$((pass + fail))
printf '\n%d/%d services healthy.\n' "$pass" "$total"

if [ "$fail" -gt 0 ]; then
  exit 1
fi
exit 0
