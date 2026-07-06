# Test Automation Summary — Story 0.3

**Generated:** 2026-07-06
**Scope:** Dev docker-compose platform (Postgres + Kafka KRaft + ES + Redis + Apicurio + MinIO + OPA)
**Skill:** bmad-qa-generate-e2e-tests
**Story:** 0.3

---

## Generated Tests

### Rego unit tests (OPA `opa test` framework)
- [x] `platform/policies/opa/kafka-topic-creation_test.rego` — 5 cases (2 deny, 3 allow)
- [x] `platform/policies/opa/schema-registration_test.rego` — 1 case (allow stub contract pin)

### Infra static-validation suite (bash)
- [x] `dev/scripts/test-infra.sh` — 27 deterministic checks (runs without docker)

### Existing tests preserved
- [x] `dev/scripts/smoke.sh` — 7-service runtime probe (operator runs after `docker compose up -d`)

---

## Coverage

| Surface | Mechanism | Cases | Status |
|---|---|---|---|
| Kafka topic admission (ADR-19) | rego `opa test` | 5 (2 deny, 3 allow) | ✅ 5/5 |
| Schema admission stub | rego `opa test` | 1 (allow=true) | ✅ 1/1 |
| Compose YAML well-formed | `python3 -c "yaml.safe_load"` | 1 | ✅ |
| 7 required services present | yaml dict lookup | 7 | ✅ 7/7 |
| Healthchecks on long-running services | yaml dict lookup | 1 (sweep) | ✅ |
| Kafka KRaft envs (broker,controller, no ZK, auto-create=false) | yaml env inspection | 1 (sweep) | ✅ |
| Redis `--maxmemory-policy allkeys-lru` | yaml command inspection | 1 | ✅ |
| Image tag pins (no `:latest` except allowlist) | yaml image regex | 1 (sweep) | ✅ |
| `.env.example` has 6 required keys | grep | 6 | ✅ 6/6 |
| `dev/.env` is gitignored | `git check-ignore -v` | 1 | ✅ |
| OPA policy files have `package` declaration | grep | 2 | ✅ 2/2 |
| OPA rego test files exist | `[ -f … ]` | 2 | ✅ 2/2 |
| `opa test` (when opa CLI installed) | `opa test` | 1 (sweep) | ✅ 6/6 rego tests |
| `smoke.sh` is valid bash | `bash -n` | 1 | ✅ |
| `smoke.sh` is executable | `[ -x … ]` | 1 | ✅ |
| README documents `docker compose` + smoke | grep | 1 | ✅ |
| Maven regression gate | `mvn -pl util -am test` | 21 (15+2+4) | ✅ 21/21 |

**API endpoints (host-port) — runtime probe (not in static suite):** Postgres `5432`, Kafka `9092`, ES `9200`, Redis `6379`, Apicurio `8081`, MinIO `9000`, OPA `8181`. Covered by `dev/scripts/smoke.sh`.

**E2E UI tests:** N/A — Story 0.3 is infrastructure only, no UI. Epic 1+ application stories will introduce E2E (Playwright/Cypress) when UI lands.

---

## Gaps auto-applied (during this QA pass)

These were detected while generating tests and fixed in-place before reporting:

1. **OPA test syntax gap** — first attempt used `input := {…}` (variable shadowing of the global `input`). Refactored to the idiomatic `with input as {…}` directive that OPA's test runner supports.
2. **OPA rule-body `if` keyword** — first draft of test rules omitted `if` in OPA 1.x syntax. Added to match the production policy's `deny[msg] if { … }` shape (same gap the story 0.3 Debug Log noted for the policy file).
3. **Healthcheck rule too strict** — first draft of the static check required healthchecks on every service, including one-shot init sidecars (`kafka-init`). Tightened to exclude `*-init` services with a comment explaining why.
4. **Maven regression verified** — re-ran `mvn -pl util -am test` after adding test files. Result: 21/21, BUILD SUCCESS, zero regressions (matches Story 0.2 baseline).

---

## How to run

```bash
# Rego policy tests (needs `opa` CLI on PATH)
opa test platform/policies/opa/ -v

# Static infra validation (no docker needed)
bash dev/scripts/test-infra.sh

# Runtime smoke (needs `docker compose up -d` first)
bash dev/scripts/smoke.sh

# Maven regression (Story 0.2 baseline)
mvn -pl util -am test
```

---

## Validation against `checklist.md`

| Item | Status |
|---|---|
| API tests generated (if applicable) | ✅ rego unit tests for the 2 OPA policies |
| E2E tests generated (if UI exists) | ⏭ N/A (no UI in Story 0.3; arrives with Epic 1+) |
| Tests use standard test framework APIs | ✅ `opa test` (regos) + bash + `python3 -c "yaml.safe_load"` |
| Tests cover happy path | ✅ 3 allow cases + 1 allow-stub case |
| Tests cover 1-2 critical error cases | ✅ 2 deny cases (no retention_ms, no retention_bytes) |
| All generated tests run successfully | ✅ 6/6 rego + 27/27 static + 21/21 Maven |
| Tests use proper locators (semantic, accessible) | ⏭ N/A (no UI) |
| Tests have clear descriptions | ✅ `test_deny_when_no_retention_*` style names |
| No hardcoded waits or sleeps | ✅ static + rego tests are deterministic, no `sleep` |
| Tests are independent (no order dependency) | ✅ each test asserts a single condition |
| Test summary created | ✅ this file |
| Tests saved to appropriate directories | ✅ `platform/policies/opa/`, `dev/scripts/` |
| Summary includes coverage metrics | ✅ table above |

---

## Next steps for the project

- Story 0.4 (CI scaffold with testcontainers) should wire `dev/scripts/test-infra.sh` and `opa test platform/policies/opa/` into the CI pipeline.
- Story 10.3 fills in the real schema-admission policy — the `_test.rego` for that policy will need to be expanded (the current 1-case test pins the stub contract).
- When application services land (Epic 1+), Playwright/Cypress E2E tests will use the ports documented in `dev/README.md`.
