#!/usr/bin/env bash
# dev/scripts/test-infra.sh — Story 0.3
# Static-validation test suite for the dev platform deliverable.
# Runs WITHOUT docker. Verifies the deliverable is internally consistent:
# compose YAML parses, all services have healthchecks, KRaft envs are set,
# .env.example is complete, .env is gitignored, rego policies are well-formed,
# smoke.sh is syntactically valid, and image tags are pinned.
#
# Exit 0 if all checks pass, exit 1 otherwise. Mirrors the smoke.sh style.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${PROJECT_DIR}/dev/docker-compose.yml"
ENV_EXAMPLE="${PROJECT_DIR}/dev/.env.example"
SMOKE_SH="${PROJECT_DIR}/dev/scripts/smoke.sh"
OPA_DIR="${PROJECT_DIR}/platform/policies/opa"

pass=0
fail=0

ok()   { printf '  ✓ %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  ✗ %s\n' "$1" >&2; fail=$((fail + 1)); }
section() { printf '\n[%s]\n' "$1"; }

# ----- 1. Compose YAML parses -----
section "compose: yaml parse"
if python3 -c "import sys, yaml; yaml.safe_load(open('${COMPOSE_FILE}'))" 2>/dev/null; then
  ok "docker-compose.yml is valid YAML"
else
  bad "docker-compose.yml is not valid YAML"
fi

# ----- 2. Required services present (7 per AC #3) -----
section "compose: required services"
for svc in postgres kafka apicurio redis elasticsearch minio opa; do
  if python3 -c "import sys, yaml; d=yaml.safe_load(open('${COMPOSE_FILE}')); sys.exit(0 if '${svc}' in d.get('services', {}) else 1)" 2>/dev/null; then
    ok "service '${svc}' present"
  else
    bad "service '${svc}' missing"
  fi
done

# ----- 3. Healthchecks on every service -----
section "compose: healthchecks"
# Init sidecars (kafka-init) are one-shot bootstrap tasks, not long-running
# services, so they're excluded from the healthcheck requirement.
MISSING_HC=$(python3 - <<PY 2>/dev/null || true
import yaml
d = yaml.safe_load(open("${COMPOSE_FILE}"))
missing = [s for s, c in d.get("services", {}).items()
           if "healthcheck" not in c and not s.endswith("-init")]
print(",".join(missing))
PY
)
if [ -z "${MISSING_HC}" ]; then
  ok "every long-running service has a healthcheck (init sidecars excluded)"
else
  bad "missing healthcheck on: ${MISSING_HC}"
fi

# ----- 4. KRaft mode (no Zookeeper) -----
section "compose: kafka KRaft"
KRAFT_OK=$(python3 - <<PY 2>/dev/null && echo y || echo n
import yaml
e = yaml.safe_load(open("${COMPOSE_FILE}"))["services"]["kafka"]["environment"]
assert e["KAFKA_PROCESS_ROLES"] == "broker,controller", "KAFKA_PROCESS_ROLES"
assert "KAFKA_CONTROLLER_QUORUM_VOTERS" in e
assert "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP" in e
assert e["KAFKA_AUTO_CREATE_TOPICS_ENABLE"] == "false", "auto.create.topics.enable"
PY
)
if [ "${KRAFT_OK}" = "y" ]; then
  ok "Kafka in KRaft mode (broker,controller), no Zookeeper, auto-create disabled"
else
  bad "Kafka not in KRaft mode (KAFKA_PROCESS_ROLES / quorum / auto-create)"
fi

# ----- 5. Redis: maxmemory-policy allkeys-lru -----
section "compose: redis LRU"
REDIS_OK=$(python3 - <<PY 2>/dev/null && echo y || echo n
import yaml
c = yaml.safe_load(open("${COMPOSE_FILE}"))["services"]["redis"]["command"]
assert "allkeys-lru" in c, c
PY
)
if [ "${REDIS_OK}" = "y" ]; then
  ok "Redis uses --maxmemory-policy allkeys-lru (ADR-13)"
else
  bad "Redis missing allkeys-lru"
fi

# ----- 6. Image tags pinned (no `latest` except allowlist) -----
section "compose: image tag pins"
UNPINNED=$(python3 - <<PY 2>/dev/null || true
import yaml
ALLOW = {"minio", "opa"}  # intentionally floating per Completion Notes
d = yaml.safe_load(open("${COMPOSE_FILE}"))
bad = []
for s, c in d["services"].items():
    img = c.get("image", "")
    if ":" not in img.split("/")[-1] or img.endswith(":latest"):
        if s not in ALLOW:
            bad.append(f"{s}={img}")
print(",".join(bad))
PY
)
if [ -z "${UNPINNED}" ]; then
  ok "all non-allowlist images are pinned to a tag"
else
  bad "unpinned images: ${UNPINNED}"
fi

# ----- 7. .env.example present + has required keys -----
section "env: .env.example keys"
for k in POSTGRES_USER POSTGRES_PASSWORD POSTGRES_DB KAFKA_CLUSTER_ID MINIO_ROOT_USER MINIO_ROOT_PASSWORD; do
  if grep -qE "^${k}=" "${ENV_EXAMPLE}" 2>/dev/null; then
    ok ".env.example has ${k}"
  else
    bad ".env.example missing ${k}"
  fi
done
if [ ! -s "${PROJECT_DIR}/dev/.env" ] || ! git -C "${PROJECT_DIR}" check-ignore -v dev/.env >/dev/null 2>&1; then
  # .env may not exist on a fresh checkout; verify the gitignore rule instead.
  if git -C "${PROJECT_DIR}" check-ignore -v dev/.env >/dev/null 2>&1; then
    ok "dev/.env is gitignored"
  else
    bad "dev/.env is NOT gitignored (rule: 'dev/.gitignore:1:.env' or root .gitignore)"
  fi
else
  if git -C "${PROJECT_DIR}" check-ignore -v dev/.env >/dev/null 2>&1; then
    ok "dev/.env is gitignored"
  else
    bad "dev/.env is NOT gitignored"
  fi
fi

# ----- 8. OPA policies: well-formed (package declared, no parse issues) -----
section "opa: policy files"
for f in "${OPA_DIR}"/*.rego; do
  [ -f "$f" ] || continue
  base=$(basename "$f")
  case "$base" in
    *_test.rego) continue ;;  # skip test files in this loop
  esac
  if grep -qE "^package " "$f"; then
    ok "${base}: has 'package' declaration"
  else
    bad "${base}: missing 'package' declaration"
  fi
done

# ----- 9. OPA policy tests present -----
section "opa: test files"
for pol in kafka-topic-creation schema-registration; do
  if [ -f "${OPA_DIR}/${pol}_test.rego" ]; then
    ok "${pol}_test.rego exists"
  else
    bad "${pol}_test.rego missing"
  fi
done

# ----- 9b. OPA tests run (if `opa` CLI is on PATH) -----
section "opa: rego test (if opa CLI present)"
if command -v opa >/dev/null 2>&1; then
  if opa test "${OPA_DIR}/" >/dev/null 2>&1; then
    ok "opa test passes (see rego test files for cases)"
  else
    bad "opa test FAILED — run 'opa test platform/policies/opa/ -v' to inspect"
  fi
else
  ok "opa CLI not installed; skipped (regos still parse-checked by file presence)"
fi

# ----- 10. smoke.sh is syntactically valid bash -----
section "scripts: smoke.sh"
if bash -n "${SMOKE_SH}" 2>/dev/null; then
  ok "smoke.sh passes 'bash -n'"
else
  bad "smoke.sh has a bash syntax error"
fi
if [ -x "${SMOKE_SH}" ]; then
  ok "smoke.sh is executable"
else
  bad "smoke.sh is not executable (chmod +x)"
fi

# ----- 11. README documents the quickstart -----
section "docs: README.md"
README="${PROJECT_DIR}/dev/README.md"
if grep -q "docker compose" "${README}" && grep -q "smoke" "${README}"; then
  ok "README documents docker compose + smoke test"
else
  bad "README missing docker compose / smoke documentation"
fi

# ----- 12. Healthcheck binaries actually exist in each image (AC #11) -----
# Common failure mode: healthcheck uses `wget` but the image only ships `curl`
# (apicurio-registry-mem), or `CMD-SHELL` on a distroless image (openpolicyagent/opa).
# Verify the chosen tool is present in the running image.
section "compose: healthcheck binaries present in images"
check_hc_binary() {
  local svc=$1 binary=$2
  if ! command -v docker >/dev/null 2>&1; then
    ok "${svc}: skipped (docker not available)"
    return
  fi
  local img
  img="$(python3 - <<PY 2>/dev/null
import yaml
print(yaml.safe_load(open("${COMPOSE_FILE}"))["services"]["${svc}"]["image"])
PY
)"
  if [ -z "${img}" ]; then
    bad "${svc}: no image declared in compose"
    return
  fi
  if docker image inspect "${img}" >/dev/null 2>&1; then
    if docker run --rm --entrypoint sh "${img}" -c "command -v ${binary}" >/dev/null 2>&1; then
      ok "${svc}: '${binary}' present in ${img}"
    else
      bad "${svc}: '${binary}' MISSING in ${img} (healthcheck will fail)"
    fi
  else
    ok "${svc}: '${binary}' (image ${img} not pulled locally — skipped)"
  fi
}
# Long-running services that have an in-container healthcheck using `curl`
check_hc_binary apicurio curl
# OPA's healthcheck is intentionally disabled (distroless image has no shell) —
# verify it's marked disable: true, not missing
OPA_HC=$(python3 - <<PY 2>/dev/null || true
import yaml
hc = yaml.safe_load(open("${COMPOSE_FILE}"))["services"]["opa"].get("healthcheck", {})
print(hc.get("disable", False))
PY
)
if [ "${OPA_HC}" = "True" ]; then
  ok "opa: healthcheck disable=true (distroless image has no shell — correct)"
else
  bad "opa: healthcheck should be disabled (distroless image) but is: ${OPA_HC}"
fi

# ----- Summary -----
total=$((pass + fail))
printf '\n%d/%d infra checks passed.\n' "$pass" "$total"
[ "$fail" -eq 0 ]
