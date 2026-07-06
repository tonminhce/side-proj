---
audience: security, dev, PM, release-manager
project: side-project
date: 2026-07-06
how-to-use: pre-launch compliance audit. Use with RELEASE-PROCESS.md. Pair with SECURITY-MODEL.md + COMPLIANCE-VN.md.
---

# Compliance Audit Checklist — side-project (pre-launch)

> **When to use:** T-2 weeks before v1.0 release (per `RELEASE-PROCESS.md` §1).
> **Goal:** Verify compliance with PDPD (Vietnam) + PCI-DSS v4.0 + Open Source licenses + GDPR (future).
> **Pass criteria:** 100% of "MUST" items below. "SHOULD" items can be deferred with documented reason.

---

## A. PCI-DSS v4.0 (per R-15 / ADR-23)

### Card-handling scope (MUST)

- [ ] **No PAN stored in any database** (verify by inspecting all schemas)
  - `psql -c "SELECT table_name FROM information_schema.columns WHERE column_name ~ '[0-9]{13,19}';"`
  - Expected: 0 results
- [ ] **No PAN logged** (verify by inspecting all logging statements)
  - `grep -r "log\\..*\\(card\\|pan\\|number\\)" services/ frontend/`
  - Expected: 0 PAN-pattern matches
- [ ] **No PAN in URL paths** (verify by inspecting all API routes)
- [ ] **Stripe Elements iframe used everywhere card is collected** (per ADR-23)
  - `grep -r "<input.*card" services/ frontend/`
  - Expected: 0 raw card inputs

### Card-handling scope (SHOULD)

- [ ] Default request-body logger is deny-listed (per R-15)
- [ ] OTel log redaction pattern is configured
- [ ] All log statements have test that verifies no PAN-shaped fields

### Authentication (MUST)

- [ ] Password hashing is Argon2id (per Story 5.4)
  - `grep -r "Argon2id\\|BCrypt\\|SCrypt" services/customer/`
  - Expected: Argon2id (not BCrypt or SCrypt)
- [ ] No passwords in plain text
- [ ] No passwords in logs
- [ ] MFA TOTP mandatory for staff+admin (per Story 5.4)
- [ ] Account lockout after 5 failed attempts (per FR-76 / AT-02)

### Encryption (MUST)

- [ ] TLS 1.3 minimum for all public-facing endpoints
- [ ] mTLS for all service-to-service
- [ ] Secrets in Vault, not in repo or env files (per ADR-18)
  - `find . -name "*.env*" -not -path "*/node_modules/*"`
  - Expected: only .env.example files
- [ ] Database connections use TLS

### Logging (MUST)

- [ ] All log lines are structured JSON
- [ ] All log lines include traceId + spanId (for OTel correlation)
- [ ] No PAN, CVV, or card-shaped data in any log
- [ ] Audit log: every admin action is logged (per FR-7, FR-63)
- [ ] Log retention: 7 years for VN tax-related, 90 days for ops (per addendum §A3)

### Code review (MUST)

- [ ] Every PR has been reviewed by ≥ 1 senior dev
- [ ] Every PR has CI green (per `CONTRIBUTING.md`)
- [ ] Archunit tests pass (Modulith module boundaries enforced)
- [ ] Avro compat check passes (backward + forward)

### Vulnerability scanning (MUST)

- [ ] OWASP dependency check: 0 critical, 0 high vulnerabilities
  - `mvn -B org.owasp:dependency-check-maven:check`
- [ ] Container image scan (Trivy): 0 critical
  - `trivy image side-project/catalog-prod:1.0.0`
- [ ] No hardcoded secrets (per `SECURITY-MODEL.md` §12)
  - `grep -r "password\\|api_key\\|secret" services/ --include="*.java"`
  - Review each match for hardcoded values

### Incident response (MUST)

- [ ] On-call rotation set
- [ ] Runbooks exist for every alert (per `ALERTING-RUNBOOK.md`)
- [ ] Post-mortem template exists (per `BUG-TRIAGE.md` §5)
- [ ] DR procedures documented (per `DISASTER-RECOVERY.md`)

---

## B. Vietnam PDPD (per FR-46 / LC-01 / `COMPLIANCE-VN.md`)

### Right of access (MUST)

- [ ] Customer data export endpoint exists and works (per FR-46)
  - `curl -X POST http://localhost/api/customer/v1/me/export`
  - Expected: 200 + JSON/ZIP of all customer data
- [ ] `customer_data_registry` table is populated (per FR-46)
  - `psql -c "SELECT * FROM customer_data_registry;"`
  - Expected: at least one row per service
- [ ] Export time < 30 days (PDPD requirement)
- [ ] Export contains all PII fields per registry

### Right to be forgotten (MUST)

- [ ] R2F endpoint exists and works (per FR-49)
  - `curl -X POST http://localhost/api/customer/v1/me/forget`
  - Expected: 200 + PII deleted + order history anonymized
- [ ] Hard-delete (PII) verified — user.email is null after R2F
- [ ] Anonymize (order history) verified — customer_uuid is null after R2F
- [ ] R2F is irrevocable (no undo path)
- [ ] R2F is recorded in `audit_trail` (for compliance evidence)

### Consent (MUST)

- [ ] Consent capture at registration (per FR-74)
- [ ] Consent withdrawal endpoint
- [ ] Data usage documented in privacy policy (legal team, not us)

### Data flow (MUST)

- [ ] Data flow documentation (legal team, not us)
  - Where does data come from?
  - Where does it go?
  - Who can access it?
  - How long is it retained?
- [ ] Cross-border transfer documented (per COMPLIANCE-VN.md §2)

### Breach notification (MUST)

- [ ] 72-hour breach notification runbook (legal team + SRE)
- [ ] Status page exists for breach announcements
- [ ] Designated contact at Vietnam PDPC

---

## C. Vietnamese Tax Invoice Decree (per FR-78 / LC-03 / `COMPLIANCE-VN.md`)

### Tax-invoice content (MUST)

- [ ] Tax authority code (MST) is present
- [ ] Merchant name is correct
- [ ] Invoice number follows serial prefix + sequence
- [ ] Issue date is correct
- [ ] Buyer info is correct
- [ ] Items with name, qty, unit price
- [ ] Subtotal, tax, total amounts are correct
- [ ] QR code is valid

### Tax-invoice compliance (MUST)

- [ ] Invoice is generated within 24h of transaction (verify with test data)
- [ ] Daily batch upload to tax authority works (per Story 9.3)
- [ ] CI gate: InvoiceService fails-fast without credentials (per Q5 closure)
- [ ] Idempotency: same `tax_invoice_id` doesn't generate duplicate (per FR-78)
- [ ] 7-year retention of all invoices (per Vietnam tax law)
- [ ] Invoice file (PDF) is stored securely (S3 with encryption)

### Operational (MUST)

- [ ] Token (tax authority API) is in Vault, not in repo (per ADR-26 + Q5 closure)
- [ ] Credential row in `vietnam_tax_authority_credential` table is set
- [ ] Accountant sign-off is recorded (`reviewed_at` + `reviewed_by`)
- [ ] Daily cron job is configured (per Story 9.3)
- [ ] Failed uploads retry with exponential backoff

### Validation (MUST)

- [ ] Test invoice round-trip: create → upload → verify on tax authority portal
- [ ] Test idempotency: same input → same invoice
- [ ] Test fail-fast: remove credentials → service refuses to start
- [ ] Test format: PDF matches Circular 78/2021/TT-BTC format

---

## D. General Security Hardening (per `SECURITY-MODEL.md`)

### Authentication (MUST)

- [ ] JWT tokens are RS256 (per FR-74)
- [ ] JWT key is rotated quarterly (per NFR-SEC-4)
- [ ] Service-account JWT is used for service-to-service (per FR-74)
- [ ] Session cookies are httpOnly + secure + sameSite=lax
- [ ] No auth tokens in localStorage (PCI compliance)
- [ ] No auth tokens in URLs (logged in proxy)

### Authorization (MUST)

- [ ] RBAC is server-side only (per FR-74)
- [ ] Customer can only access their own data
- [ ] Staff can only access operations data
- [ ] Admin can only access admin data
- [ ] Service-account JWT has correct audience (per FR-74)
- [ ] No client-side role checks (per FR-74)

### Input validation (MUST)

- [ ] All DTOs use Bean Validation (per SECURITY-MODEL §10)
- [ ] All SQL is parameterized (no string concatenation)
- [ ] All user input is sanitized
- [ ] No `dangerouslySetInnerHTML` without sanitization (frontend)
- [ ] All forms use Zod schema (frontend)

### Web security (MUST)

- [ ] CSP headers configured (frontend)
- [ ] HTTPS-only (no HTTP fallback)
- [ ] HSTS enabled
- [ ] X-Content-Type-Options: nosniff
- [ ] X-Frame-Options: DENY (or SAMEORIGIN)
- [ ] Referrer-Policy: strict-origin-when-cross-origin

### Secrets (MUST)

- [ ] No `.env` files in repo (per ADR-18)
  - `git log --all -- "*.env" "*.env.*"`
  - Expected: 0 results
- [ ] No hardcoded secrets in code
  - `grep -rE "(api[_-]?key|password|secret|token)\\s*=\\s*[\"'][^\"']+[\"']" --include="*.java" --include="*.ts" .`
  - Review each match
- [ ] Vault policies are role-based (per `SECURITY-MODEL.md` §7)
- [ ] HMAC keys (event signing) are per-service, quarterly rotation (per ADR-20)

### Observability (MUST)

- [ ] OTel instrumentation is enabled (per ADR-16)
- [ ] OTel log redaction is configured
- [ ] All alerts have runbooks (per `ALERTING-RUNBOOK.md`)
- [ ] No PAN in any alert message
- [ ] Incident response plan exists (per `BUG-TRIAGE.md`)

### Network (MUST)

- [ ] TLS 1.3 minimum
- [ ] mTLS service-to-service
- [ ] Network policies in K8s (deny by default, allow explicit)
- [ ] No public IPs for internal services

---

## E. Open Source License Compliance (MUST)

- [ ] All 3rd-party deps have approved licenses
  - `mvn -B license:check`
- [ ] No GPL-licensed deps in proprietary product (we have util/ as open source; check no viral deps)
- [ ] License attribution in README
- [ ] No deps with unknown license

### Allowed licenses

- Apache 2.0, MIT, BSD, MPL 2.0: ✅ allowed
- LGPL: ⚠️ allowed for libraries (dynamic linking), not for code
- GPL: ❌ avoid in v1 (unless contributing back as open source)
- AGPL: ❌ avoid (network copyleft)
- Proprietary: ⚠️ evaluate case-by-case

---

## F. Operational Readiness (MUST)

### Monitoring (per `OBSERVABILITY-RUNBOOK.md`)

- [ ] Grafana dashboards provisioned from Git (per Story 10.1)
- [ ] Prometheus rules in Git (per Story 10.3)
- [ ] All alerts have runbooks
- [ ] SLOs defined and tracked (per NFR-AVAIL-1)
- [ ] On-call rotation set
- [ ] Status page exists

### Disaster recovery (per `DISASTER-RECOVERY.md`)

- [ ] DR procedures documented
- [ ] RTO/RPO targets defined (per DISASTER-RECOVERY §1)
- [ ] Quarterly DR drills scheduled
- [ ] Backup verification procedure exists
- [ ] Multi-region / cross-AZ setup verified

### Incident response (per `BUG-TRIAGE.md`)

- [ ] On-call rotation set
- [ ] Severity classification defined
- [ ] Communication templates ready
- [ ] Post-mortem template ready
- [ ] Blameless culture documented

### Capacity (per `CAPACITY-PLANNING.md`)

- [ ] Launch capacity targets defined (per addendum A3)
- [ ] Auto-scaling configured (HPA / VPA)
- [ ] Load test executed (target 2x production)
- [ ] Capacity plan reviewed (quarterly)

### Feature management (per `FEATURE-FLAGS.md`)

- [ ] All flags have owners
- [ ] All flags have `expires_after`
- [ ] Kill switches tested (per FR-XX risk-mitigation flags)
- [ ] No abandoned flags

### Code quality (per `CONTRIBUTING.md`)

- [ ] All PRs reviewed
- [ ] CI is green
- [ ] Spotless + Prettier pass
- [ ] Archunit tests pass (Modulith boundary enforcement)
- [ ] Avro compat check passes
- [ ] Coverage didn't drop

---

## G. Documentation (MUST)

- [ ] README has Quick Start, Architecture Overview, Contributing link
- [ ] All 11 epics have AC in `epics.md`
- [ ] All 26 ADRs documented in `architecture.md`
- [ ] All 15 risks in `RISK-REGISTER.md` with mitigations
- [ ] All 5 Open Questions resolved (per PRD §13)
- [ ] Onboarding doc exists (`AGENT-ONBOARDING.md`)
- [ ] Glossary exists (`GLOSSARY.md`)
- [ ] PR template + checklist (per `CONTRIBUTING.md`)

---

## H. Pre-launch audit checklist (final)

Before declaring "READY for v1.0":

- [ ] **A. PCI-DSS:** all MUST items ✅
- [ ] **B. Vietnam PDPD:** all MUST items ✅
- [ ] **C. Vietnam Tax Invoice:** all MUST items ✅
- [ ] **D. General Security:** all MUST items ✅
- [ ] **E. Open Source License:** all MUST items ✅
- [ ] **F. Operational Readiness:** all MUST items ✅
- [ ] **G. Documentation:** all MUST items ✅
- [ ] All "SHOULD" items have a documented reason if deferred

---

## I. Sign-off

| Role | Name | Sign-off date | Notes |
|---|---|---|---|
| Security Lead | _________ | _________ | |
| Tech Lead (Architect) | _________ | _________ | |
| PM | _________ | _________ | |
| Compliance Officer (external) | _________ | _________ | Vietnamese attorney / accountant |
| Release Manager | _________ | _________ | |

When all sign-offs are in, attach this checklist to the release notes.

---

## J. Cross-references

- **Security model:** `SECURITY-MODEL.md`
- **Vietnam compliance deep-dive:** `COMPLIANCE-VN.md`
- **Risk register:** `RISK-REGISTER.md`
- **Release process:** `RELEASE-PROCESS.md`
- **Disaster recovery:** `DISASTER-RECOVERY.md`
- **Capacity planning:** `CAPACITY-PLANNING.md`
- **PR conventions:** `CONTRIBUTING.md`
- **Operational runbook:** `DEVOPS-RUNBOOK.md`
- **Bug triage:** `BUG-TRIAGE.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
