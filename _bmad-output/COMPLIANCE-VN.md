---
audience: dev, PM, security, accountant (external)
project: side-project
date: 2026-07-06
how-to-use: Vietnam-specific compliance deep-dive. Pair with SECURITY-MODEL.md (PCI scope).
---

# Vietnam Compliance Deep-Dive — side-project

> **Scope:** Vietnamese laws + regulations affecting the side-project reference impl.
> **Audiences:** Dev (implementing), PM (planning), Security (audit), Vietnamese accountant (validating business logic).
> **What this is NOT:** legal advice. Vietnamese tax / PDPD laws are complex; consult a Vietnamese attorney / accountant for production-grade compliance.

---

## 1. Three main laws / regulations affecting side-project

| Law | Number | What it covers | Affects |
|---|---|---|---|
| **PDPD** (Personal Data Protection Decree) | Decree 13/2023/NĐ-CP | User data collection, storage, processing, deletion rights | All services (especially customer + payment) |
| **Tax Invoice Decree** | Decree 123/2020/NĐ-CP + Circular 78/2021/TT-BTC | Tax-invoice format, content, transmission | Invoice service (Sprint 9) |
| **Cybersecurity Law** | Law on Cyberinformation Security 2018 | Data localization for Vietnamese users (debated) | All services (debatable) |

---

## 2. PDPD (Decree 13/2023/NĐ-CP)

### What is PDPD?

Vietnam's Personal Data Protection Decree. Effective 1 July 2023. Regulates:
- Collection, storage, processing, transfer of personal data
- Rights of data subjects (access, deletion, consent)
- Obligations of data controllers / processors
- Penalties: up to 5% of revenue, max VND 5 billion (~USD 200k)

### What counts as "personal data"?

Any information that identifies a specific person:
- Name, email, phone, address
- ID number (CMND/CCCD)
- Bank account (when linked to a person)
- IP address (when linked to a person)
- Cookies (when persistent)
- Behavioral data (purchase history, browsing)

### What does side-project do?

#### FR-46 / Story 5.2 — Customer data export (right of access)

Vietnamese users can request all data we hold about them. We provide:
- `GET /bff/storefront/me/export` returns JSON/ZIP of all customer data
- Source-of-truth: `customer_data_registry` table (per FR-46) which lists (service, table, columns) for every PII field
- Response time: < 30 days (PDPD requirement)

#### FR-49 / Story 5.2 — Right to be forgotten (R2F)

Vietnamese users can request deletion of all their data. We:
- `POST /bff/storefront/me/forget` hard-deletes PII (name, email, phone, address)
- Anonymizes order history (replaces customer_uuid with "deleted-user-N")
- Keeps anonymous records for tax compliance (7-year retention per Vietnam tax law)
- Logs the deletion in `audit_trail` (irrevocable record)

#### Consent capture

Vietnamese users must explicitly consent to data collection. We:
- Capture consent during registration (Story 5.4)
- Allow consent withdrawal (Story 5.4)
- Document data usage in privacy policy (legal team, not us)

#### Data localization

PDPD allows cross-border transfer with consent. We:
- Store data in Vietnam region (per Vietnamese customers' preference)
- Use Vietnamese providers (Stripe is international; we accept that for payment)
- Document data flow in `data-flow.md` (legal team, not us)

#### Breach notification

Vietnamese PDPD requires 72-hour notification to:
- The Vietnam Personal Data Protection Commission
- Affected data subjects (if breach is severe)

We:
- Have a `breach-notification.md` runbook (legal team)
- Have a `breach-status` alert in `ALERTING-RUNBOOK.md`

### What we DON'T do

- ❌ We DON'T process sensitive personal data (political opinions, religious beliefs, biometric data, health data) — these require explicit separate consent
- ❌ We DON'T sell user data
- ❌ We DON'T use user data for automated decision-making that affects legal rights
- ❌ We DON'T transfer data outside Vietnam without consent

---

## 3. Tax Invoice Decree (Decree 123/2020/NĐ-CP + Circular 78/2021/TT-BTC)

### What is the Tax Invoice Decree?

Vietnam's e-invoice regulation. Effective 1 July 2022. Replaces paper invoices. Requires:
- Serialized invoice number (allocated by tax authority)
- QR code for verification
- Submission to Vietnam tax authority's portal (General Department of Taxation)
- Daily batch upload (within 24h of issuance)

### Tax invoice content (required)

| Field | Example | Source |
|---|---|---|
| Tax authority code | "MST: 0123456789" | `vietnam_tax_authority_credential.merchant_tax_code` |
| Invoice number | "AA-0000001" | `tax_invoice_sequence.current_value` |
| Issue date | "2026-07-06" | `invoices.issued_at` |
| Seller info | "Công ty TNHH ABC" | `vietnam_tax_authority_credential.merchant_name` |
| Buyer info | "Nguyen Van A" | `orders.shipping_address` |
| Items | Product name, qty, unit price | `order_lines` (snapshot) |
| Total before tax | 30,000,000 VND | `invoices.amount_cents` |
| Tax | 3,000,000 VND | `invoices.tax_cents` |
| Total | 33,000,000 VND | `invoices.total_cents` |
| QR code | (binary) | `invoices.qr_data` |

### What does side-project do?

#### Architecture (per ADR-26 + Q5 closure)

- **`vietnam_tax_authority_credential` table** (Q5 closure schema) — per-merchant credentials
- **`tax_invoice_sequence` table** — atomic `SELECT FOR UPDATE` allocator
- **InvoiceService** (Sprint 9, Story 9.2) — issues invoices, uses Jasper + QR
- **Daily batch** (Story 9.3) — Quartz cron at 23:00 daily
- **CI gate** — InvoiceService fails-fast without credential row (per Q5 closure)

#### Q5 closure status (closed)

Per `architecture.md` §"Open Questions — Resolved by Architecture" + `architecture-detail.md` §"Detail: ADR-26":
- ✅ Q5 RESOLVED (ADR-26 binds implementation; merchant credentials collected via `vietnam_tax_authority_credential` table)
- ✅ Sprint 9 Story 9.2b = accountant-input-collection ceremony (Sprint Lead runs the input-gathering)
- ⚠️ The specific tax-authority registration numbers + merchant credentials still need Vietnamese accountant input (per Story 9.2b)

### Tax rates (Vietnam)

- **Standard VAT:** 10%
- **Reduced VAT:** 5% (some goods/services)
- **Zero VAT:** 0% (exports, certain medical, etc.)

For side-project (B2C ecommerce in v1): 10% standard VAT applies to most goods.

### Invoice transmission

- **Method:** Vietnam tax authority's e-invoice portal (https://hoadondientu.gdt.gov.vn/)
- **Frequency:** Daily (every 24h)
- **Format:** XML or API call (per tax authority's spec)
- **Authentication:** Token (per merchant) stored in Vault at `secret/tax/<merchant_tax_code>/tax_authority_token`

### Deadlines

- **Issuance:** Within 24h of transaction
- **Transmission:** Daily by 23:59
- **Customer receipt:** Immediate (download link + email)
- **Tax filing:** Monthly (legal team, not us)

### What we DON'T do

- ❌ We DON'T issue paper invoices (illegal in Vietnam since 1 July 2022)
- ❌ We DON'T issue invoices without a tax-authority credential (per Q5 closure)
- ❌ We DON'T issue invoices with the wrong tax code (per Q5 closure)
- ❌ We DON'T issue invoices without the QR code (per Circular 78/2021)

---

## 4. Cybersecurity Law (2018)

### What is the Cybersecurity Law?

Vietnam's cybersecurity law. Among many provisions:
- Data localization for Vietnamese users (debated; "data centers in Vietnam" — for certain sectors)
- Reporting cybersecurity incidents to the Authority of Information Security
- Cooperation with Vietnamese law enforcement

### What does side-project do?

- We store data in **Singapore region AWS** (per addendum §A3) — this may not be compliant with strictest reading of "data localization in Vietnam." A Vietnamese attorney / accountant should validate this for production-grade compliance.
- We have a `breach-notification.md` runbook (legal team) for incident reporting
- We cooperate with Vietnamese law enforcement upon valid request (legal team)

### What we DON'T do

- ❌ We DON'T operate critical infrastructure (banks, telecoms) — so data localization is debatable
- ❌ We DON'T process government data — no special obligations
- ❌ We DON'T process health data — no special obligations

---

## 5. Other VN regulations (potentially applicable)

| Regulation | Affects | v1 status |
|---|---|---|
| **Consumer Protection Law** 2010 | Refund policy, warranty | FR-41 (exchange-first), FR-43 (cumulative refund safety) |
| **E-commerce Decree** 18/2020/NĐ-CP | Online selling | Mostly OK; may need additional disclosures |
| **Anti-Money Laundering Law** | Financial transactions | Stripe handles; we don't process funds directly |
| **Personal Income Tax** | For individuals | Not applicable to us |

### E-commerce Decree specifics (per Decree 18/2020/NĐ-CP)

- Display clear pricing (incl. VAT, shipping) → FR-65/66/67 covers this
- Display seller info → FR-46/49 covers this
- Allow returns / refunds → FR-40..44 covers this
- Display terms of service → legal team, not us
- Cookie consent → legal team, not us

---

## 6. Implementation checklist (PDPD)

- [x] **FR-46:** Customer data export endpoint (Story 5.2)
- [x] **FR-49:** Right-to-be-forgotten endpoint (Story 5.2)
- [x] **`customer_data_registry` table** (per FR-46)
- [x] **Customer-data audit** (Story 5.2)
- [ ] **Privacy policy** (legal team; not us)
- [ ] **Cookie banner** (frontend)
- [ ] **Consent capture at registration** (Story 5.4)
- [ ] **Consent withdrawal** (Story 5.4)
- [ ] **Breach-notification runbook** (legal team + SRE)
- [ ] **Data flow documentation** (legal team)
- [ ] **72-hour notification automation** (legal team + SRE)

---

## 7. Implementation checklist (Tax Invoice)

- [x] **FR-78:** Tax-invoice generation (Sprint 9, Story 9.2)
- [x] **FR-78:** Daily register batch (Sprint 9, Story 9.3)
- [x] **ADR-26:** Vietnamese tax-invoice implementation
- [x] **`vietnam_tax_authority_credential` table** (Q5 closure)
- [x] **`tax_invoice_sequence` table** (atomic allocator)
- [x] **Jasper template** with Vietnamese fonts (util's fonts)
- [x] **QR code** (util's `QRCodeUtil`)
- [x] **Idempotency** on retry
- [x] **CI gate** (fail-fast without credentials)
- [x] **Accountant input template** (Q5 closure)
- [x] **Q5 closure procedure** (architect-defined)
- [x] **Story 9.2b** (accountant-input ceremony)
- [ ] **Accountant actual credentials** (Q5 follow-up, accountant must provide)
- [ ] **Tax authority portal registration** (accountant)
- [ ] **Daily Quartz cron tested in prod** (Sprint 9)
- [ ] **Tax-filing process** (legal team, monthly)

---

## 8. Sprint 9 Q5 closure status (final)

Per `architecture-detail.md` §"Detail: ADR-26" → "Q5 closure procedure":

1. **Before Sprint 9 begins:**
   - Sprint Lead sends `vietnam_tax_authority_credential` input template to Vietnamese accountant + configured merchant.
2. **Accountant returns:**
   - Filled template (MST, merchant name, address, serial prefix, serial range, API endpoint, token, active_from, reviewed_by).
3. **Sprint 9 acceptance:**
   - Story 9.2 requires a populated `vietnam_tax_authority_credential` row with `reviewed_by` set.
4. **CI gate (production):**
   - InvoiceService fails-fast at startup if no active credential row.
5. **Yearly re-review:**
   - Quartz cron alerts when next review is due (configurable, default 365 days).

This closes Q5 end-to-end. **The implementation pattern is bound; the operational follow-up is per accountant.**

---

## 9. Cross-references

- **Tax-invoice implementation:** `architecture-detail.md` §"Detail: ADR-26"
- **Q5 closure status:** `architecture.md` §"Open Questions — Resolved by Architecture" + `architecture-detail.md` §"Detail: ADR-26 → Q5 closure procedure"
- **Story 9.2 / 9.2b / 9.3:** `epics.md` §5.16
- **PDPD data export / R2F:** `SECURITY-MODEL.md` §6 (Vietnamese compliance)
- **Customer data registry:** `DATA-MODEL.md` §3 (Customer service schema)
- **Operational runbook:** `DEVOPS-RUNBOOK.md` §11 (troubleshooting)
- **Security model:** `SECURITY-MODEL.md` §6
- **Risk register:** `RISK-REGISTER.md` (R-06 VN tax-invoice, R-15 PCI scope, LC-01 PDPD)
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
