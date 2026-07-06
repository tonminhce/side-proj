---
audience: security, dev, architect
project: side-project
date: 2026-07-06
how-to-use: all security controls + R-XX mitigations. Reference when implementing auth, payment, or any data-handling code. Pair with RISK-REGISTER.md.
---

# Security Model — side-project

> **Compliance scope:** Vietnam PDPD (Decree 13/2023/NĐ-CP), PCI-DSS v4.0, Vietnamese tax-invoice (Circular 78/2021 + Decree 123/2020), GDPR (future expansion).
> **Reference implementation:** NOT a commercial product; no merchant onboarding; no sales motion.

---

## 1. Trust boundaries

| Boundary | Direction | Authentication | Authorization | Source ADR |
|---|---|---|---|---|
| **Internet → Gateway** | Inbound | TLS 1.3, no client cert | Rate-limit by IP + card-fingerprint + ASN | NFR-SEC-1, ADR-24 |
| **Gateway → BFF** | Internal | mTLS | Service-account JWT (RS256) | ADR-09, ADR-18 |
| **BFF → Service** | Internal | mTLS | Service-account JWT | ADR-09 |
| **Service → Service** | Internal | mTLS + JWT | RBAC + Saga role | ADR-12 |
| **Service → Postgres** | Internal | TLS + DB credentials | Per-service DB user | ADR-03 |
| **Service → Redis** | Internal | TLS + password | Network policy | ADR-13 |
| **Service → Kafka** | Internal | mTLS + SASL | ACL on topics | ADR-04, ADR-19 |
| **Service → Apicurio** | Internal | TLS + API key | Per-group ACL | ADR-15 |
| **Service → Vault** | Internal | Kubernetes Service Account + Vault Agent | Per-path policy | ADR-18 |
| **Browser → BFF** | HTTPS | Session cookie (httpOnly, secure, sameSite=lax) | Server-side RBAC check | ADR-10, ADR-23 |
| **Browser → Stripe** | HTTPS | Stripe Elements iframe | N/A (Stripe handles) | ADR-23 |

### Key principle: no cleartext secrets anywhere

Per ADR-18: **no `.env` files in repo**, **no hardcoded keys in code**, **no logging of credentials**.

---

## 2. Authentication

### Customer authentication (B2C, per Story 5.4)

| Method | Status | Use case |
|---|---|---|
| **Email + password** (Argon2id hashing) | **Required** | Primary auth for v1 |
| **MFA TOTP** | **Mandatory for staff/admin**; optional for customers | Defense in depth (AT-02) |
| **Magic link (passwordless)** | Optional | Convenience for repeat customers |
| **Social login (Google/Facebook)** | Deferred to v2 (per brainstorming) | Post-launch |

### Account lockout (per FR-76 / AT-02)

- 5 failed login attempts in 15 minutes → account locked
- Lock duration: configurable, default 30 minutes
- After lock: `account.locked` event emits to security-events topic
- CAPTCHA serves on subsequent attempts
- Lockout counter resets after 24 hours of inactivity

### JWT structure (per FR-74)

```json
{
  "sub": "user-uuid",
  "role": "customer | staff | admin | service-account",
  "tenant": "default",
  "iat": 1234567890,
  "exp": 1234571490,
  "scope": ["read:catalog", "write:cart"]
}
```

- **Algorithm:** RS256 (asymmetric, key rotated quarterly per NFR-SEC-4)
- **Lifetime:** 1 hour (refresh tokens for 30 days)
- **Storage:** httpOnly secure cookie (NEVER localStorage; PCI compliance)
- **Key rotation:** quarterly, 7-day overlap (Vault path `secret/jwt-signing-key`)

### Service-to-service auth (per FR-74)

- **Service-account JWT** per service (per ADR-09)
- Each service has its own identity (e.g., `service-account: catalog-service`)
- JWT signed with the same RS256 key
- Audience: target service name (prevents token replay)
- Lifetime: 5 minutes (short)

---

## 3. Authorization (RBAC)

### Roles (per FR-74)

| Role | Description | Permissions |
|---|---|---|
| `customer` | End user | Read catalog, manage own cart, place orders, view own orders, view own invoices |
| `staff` | Internal operator | Customer + view all orders, view catalog write access, view inventory, manage returns |
| `admin` | Internal power user | All staff + edit catalog, manage users, view audit trail, configure system |
| `service-account` | Internal service identity | Per-service scope (e.g., `catalog-service` can publish catalog events) |

### Permission catalog (per ADR-09)

| Permission | Roles |
|---|---|
| `read:catalog` | customer, staff, admin, catalog-service |
| `write:catalog` | admin, catalog-service |
| `read:cart` | customer, cart-service |
| `write:cart` | customer, cart-service |
| `read:order` | customer (own), staff, admin, order-service |
| `write:order` | customer (own), order-service |
| `read:user-data` | customer (own), admin, customer-service |
| `export:user-data` | customer (own), customer-service |
| `delete:user-data` | customer (own), admin, customer-service |
| `read:audit-trail` | admin, audit-service |
| `write:tax-invoice` | invoice-service, admin |

### Authorization enforcement (server-side, per RBAC pattern)

```java
@PreAuthorize("hasRole('ADMIN') or @userService.isOwner(#userId, principal.userId)")
public void deleteUserData(@PathVariable UUID userId) { ... }
```

**Rule:** All authorization checks MUST be server-side. Never trust client-side role checks. Never trust JWT claims without re-verification on the resource.

---

## 4. PCI-DSS scope (R-15 / ADR-23)

### Scope minimization

We do **not** process, store, or transmit Primary Account Numbers (PAN) anywhere in our infrastructure. This is achieved via:

- **Stripe Elements iframe** — card data is collected by Stripe's JavaScript, never sent to our servers
- **OTel log redaction** — any field matching `\d{13,19}` (PAN-shaped) is redacted at the OTel processor level
- **Default request-body logger is deny-listed** — no service logs full request bodies
- **Stripe webhook signature verification** — every incoming webhook is verified against `whsec_*` secret

### What's in scope vs out of scope

| Component | In PCI scope? | Why |
|---|---|---|
| Storefront Next.js | NO | Iframe only; never sees PAN |
| BFF (storefront) | NO | Doesn't handle card data |
| PaymentService | **PARTIAL** | Handles PaymentIntent IDs (not PAN); receives tokens, not cards |
| Postgres (per-service) | NO | Stores Stripe customer IDs, not PAN |
| Stripe webhooks handler | **PARTIAL** | Receives event objects; verifies signature |
| Stripe (external) | YES (their scope, not ours) | They handle PAN |

### Operational rules (per FR-79 / ADR-23)

1. ❌ **NEVER** log a PAN-shaped field. OTel processor redaction enforces this.
2. ❌ **NEVER** log a full request body. Default logger is deny-listed.
3. ❌ **NEVER** store PAN in any DB column.
4. ❌ **NEVER** include PAN in error messages.
5. ❌ **NEVER** use `System.out.println` — use the structured logger.
6. ✅ Always use Stripe Elements iframe for card collection.
7. ✅ Always verify Stripe webhook signatures.
8. ✅ Always rotate Stripe API keys quarterly.
9. ✅ Always run PCI pen-test before each release.

---

## 5. Card-testing defense (R-05 / ADR-24)

### Layered rate-limiting

```
Internet → Gateway (per-IP rate-limit) → Service (per-card-fingerprint limit) → BIN velocity check
```

### Rate-limit keys (per ADR-24)

| Layer | Key | Limit | Window |
|---|---|---|---|
| Gateway | `IP` | 100 req/min | 1 min |
| Gateway | `IP + ASN` | 500 req/min | 1 min |
| Service | `card-fingerprint` | 10 req/hour | 1 hour |
| Service | `BIN` (across all users) | 1000 req/hour | 1 hour |
| Customer | `customer.id` | 1000 req/hour | 1 hour |

### Time source fix (per ADR-13)

**Critical:** The Lua rate-limiter script uses `redis.call('TIME')` (Redis-server time), **NOT** the gateway's wall-clock. This avoids drift if gateway and Redis clocks desync.

```lua
-- Rate-limit Lua snippet (correct)
local time = redis.call('TIME')  -- server time
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- ... rest of token-bucket logic
```

### Detection signals

- Burst of failed payment attempts from same card-fingerprint → alert
- Multiple cards from same BIN failing → flag BIN for review
- Card-fingerprint velocity > threshold → block

### Card fingerprinting

```java
// Stripe tokens: tok_xxx. The fingerprint is the last 4 of card + bin.
// Store ONLY the token ID + fingerprint, NEVER the PAN.

public class CardReference {
    private String stripeTokenId;  // tok_xxx
    private String cardBrand;      // visa, mastercard, etc.
    private String cardLast4;     // "4242"
    private String cardBin;       // first 6 digits
    // NO PAN storage
}
```

---

## 6. Vietnamese compliance (PDPD + Tax-Invoice)

### PDPD (per FR-46, FR-49 / LC-01)

| Right | Implementation | Story |
|---|---|---|
| Right to access (data export) | `GET /bff/storefront/me/export` returns JSON/ZIP of all customer data | Story 5.2 |
| Right to be forgotten (R2F) | `POST /bff/storefront/me/forget` hard-deletes PII, anonymizes order history | Story 5.2 |
| Right to data portability | Same as access right (JSON/ZIP) | Story 5.2 |
| Consent capture | Per-data-category opt-in during onboarding | (future story) |
| Breach notification | 72-hour notification to Vietnam PDPC + affected users | (ops runbook) |

### Customer Data Registry

```sql
-- Per architecture, Story 5.2 implements this table
CREATE TABLE customer_data_registry (
    id BIGSERIAL PRIMARY KEY,
    service VARCHAR(50) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    columns JSONB NOT NULL,  -- which fields are customer PII
    export_format VARCHAR(20) NOT NULL,  -- 'json' | 'csv'
    retention_days INT,
    UNIQUE(service, table_name)
);
```

This is the source-of-truth for what data PDPD export must include.

### Vietnamese tax-invoice (per FR-78 / ADR-26)

See `architecture-detail.md` §"Detail: ADR-26" + `RISK-REGISTER.md` for full details. Summary:

- `vietnam_tax_authority_credential` table (per Q5 closure)
- Serialized number allocator (per-merchant sequence)
- Jasper template with Vietnamese fonts (already in `util/src/main/resources/fonts/`)
- QR code via util's `QRCodeUtil`
- Daily batch via Quartz cron
- CI gate: InvoiceService fails-fast without credential row

---

## 7. Secret management (per ADR-18)

### Vault structure (production)

```
secret/
├── stripe/
│   ├── STRIPE_API_KEY              # sk_live_*
│   └── STRIPE_WEBHOOK_SECRET       # whsec_*
├── events/hmac/
│   ├── catalog-service             # 32-byte HMAC key
│   ├── inventory-service
│   ├── cart-service
│   └── ... (one per service)
├── jwt/
│   └── signing-key                 # RS256 private key
├── postgres/
│   ├── catalog                     # db credentials
│   ├── inventory
│   └── ... (one per service)
├── redis/
│   └── password
├── tax/
│   └── 0123456789                  # merchant tax code → tax authority token
└── observability/
    ├── grafana-admin
    └── alertmanager-webhook
```

### Vault policy

```hcl
# Vault policy: services can only read their own secrets
path "secret/data/postgres/catalog" {
  capabilities = ["read"]
  allowed_parameters = {}
}

# Only InvoiceService can read tax credentials
path "secret/data/tax/*" {
  capabilities = ["read"]
  bound_audiences = ["invoice-service"]
}
```

### Secret rotation

| Secret | Frequency | Procedure |
|---|---|---|
| Stripe API keys | Quarterly | Create new key in Stripe dashboard, update Vault, redeploy with overlap |
| HMAC service keys | Quarterly | Generate new 32-byte key, write to Vault at `secret/events/hmac/<service>`; 7-day overlap (both old + new valid) |
| JWT signing key | Quarterly | Generate new RS256 keypair, write both public + private to Vault; rotate via JWKS endpoint |
| Postgres passwords | Annually | Rotate Vault secret, redeploy with new password |
| Tax authority tokens | On credential change | Per-merchant, manual update via Story 9.2b ceremony |

---

## 8. Event security (per ADR-20)

### HMAC-SHA-256 event signing

Every event in the outbox carries:

```json
{
  "event_id": 1234567890,
  "event_type": "orders.placed",
  "occurred_at": 1234567890000,
  "aggregate_id": 9876543210,
  "aggregate_type": "order",
  "tenant_id": "default",
  "correlation_id": "checkout-uuid",
  "causation_id": "payment.captured",
  "payload": { /* Avro record */ },
  "signatures": {
    "service": "checkout-service",
    "key_id": "v3-2026-q3",
    "hmac_sha256": "base64url-encoded-hmac"
  }
}
```

### Producer

```java
public void sign(OutboxEvent event) {
    var canonical = JsonCanonicalizer.canonicalize(eventWithoutSignatures);
    var key = vault.read("secret/events/hmac/" + serviceName);
    var hmac = HmacSha256.sign(canonical, key);
    event.setSignatures(new Signatures(serviceName, currentKeyId, hmac));
}
```

### Consumer

```java
public void verify(Event event) {
    var canonical = JsonCanonicalizer.canonicalize(eventWithoutSignatures);
    var key = vault.read("secret/events/hmac/" + event.signatures.service);
    var expectedHmac = HmacSha256.sign(canonical, key);
    if (!constantTimeEquals(event.signatures.hmac, expectedHmac)) {
        // Reject event + emit security alert
        throw new InvalidEventSignatureException();
    }
}
```

### Vault unreachable (failure mode)

Per ADR-20: **producer FAILS LOUD** (refuses to publish events) rather than signing with a stale or default key. This is intentional — silent fallback is a security risk.

---

## 9. Network security

### TLS configuration

| Component | TLS version | Cipher suites |
|---|---|---|
| Public-facing gateway | TLS 1.3 | AEAD only (AES-GCM, ChaCha20-Poly1305) |
| Service-to-service (mTLS) | TLS 1.3 | AEAD only |
| Kafka | TLS 1.3 + SASL_SSL | AEAD only |
| Postgres | TLS 1.3 | AEAD only |
| Redis | TLS 1.3 | AEAD only |
| Vault | TLS 1.3 | AEAD only |

### Service mesh (Istio or Linkerd in production)

- mTLS between all services (auto-managed)
- Authorization policies (e.g., only CatalogService can write to `catalog.*` topics)
- Network policies at namespace level (deny by default, allow explicit)

### OPA admission (per ADR-19)

```rego
# OPA policy: Kafka topic creation requires retention policy
package kubernetes.admission

deny[msg] {
    input.request.kind.kind == "KafkaTopic"
    not input.request.object.spec.config.retentionMs
    msg := "Kafka topic must have retention.ms configured"
}
```

---

## 10. Input validation (defense in depth)

### Bean Validation on DTOs (Jakarta)

```java
public record CreateProductRequest(
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9-]+$") String slug,
    @NotEmpty @Size(max = 100) List<@Valid VariantRequest> variants
) {}
```

### SQL injection prevention

- ✅ Always use JPA repositories (Hibernate parameterized queries) or `JdbcTemplate` with `?` placeholders
- ❌ Never use `String.format` or string concatenation to build SQL
- ❌ Never use `EntityManager.createNativeQuery(string)` with user input

### XSS prevention (frontend)

- ✅ React auto-escapes strings in JSX
- ✅ Use DOMPurify for any HTML rendering
- ✅ CSP headers in nginx / BFF
- ❌ Never use `dangerouslySetInnerHTML` without sanitization

### CSRF prevention

- ✅ SameSite=lax cookies (default in Next.js 15)
- ✅ Double-submit cookies for state-changing requests
- ✅ Same-origin policy enforced

---

## 11. Audit trail (per FR-7, FR-63, FR-64)

### What gets logged

| Event type | Logged where | Retention |
|---|---|---|
| Admin login / logout | audit_trail table | 2 years |
| Catalog mutation (CRUD by staff) | audit_trail table | 2 years |
| Order status change | order event log | 7 years (Vietnam tax requirement) |
| Refund issuance | refund event log | 7 years |
| User data export (PDPD) | audit_trail table + email to user | 7 years |
| User data deletion (R2F) | audit_trail + irrevocable record | 7 years |
| Tax-invoice issuance | `tax_invoice` table | 7 years (Vietnam tax) |

### Schema

```sql
CREATE TABLE audit_trail (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID NOT NULL,
    actor_role VARCHAR(20) NOT NULL,  -- 'staff' | 'admin' | 'service-account'
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id UUID,
    before_state JSONB,  -- state before mutation
    after_state JSONB,   -- state after mutation
    diff JSONB,          -- computed diff
    request_id UUID,      -- for tracing
    trace_id VARCHAR(32), -- for OTel cross-reference
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_actor ON audit_trail(actor_id, created_at);
CREATE INDEX idx_audit_resource ON audit_trail(resource_type, resource_id);
```

### What is NOT logged (per FR-79, ADR-23)

- ❌ PAN, CVV, full card numbers
- ❌ Passwords (any field matching password-shaped)
- ❌ Session tokens (only the user_id is logged)
- ❌ PII beyond what's needed for audit (e.g., email is logged but not password)

---

## 12. Hard rules — DO NOT VIOLATE

These are non-negotiable. Code review must reject PRs that violate.

1. ❌ Never log PAN, CVV, or card numbers (R-15)
2. ❌ Never store PAN in any DB column (R-15)
3. ❌ Never enable default request-body logger (R-15)
4. ❌ Never use `System.out.println` in production code
5. ❌ Never commit `.env` files (ADR-18)
6. ❌ Never hardcode secrets in code (ADR-18)
7. ❌ Never use string concatenation to build SQL
8. ❌ Never use `dangerouslySetInnerHTML` without sanitization
9. ❌ Never skip Stripe webhook signature verification
10. ❌ Never use `localStorage` for auth tokens (PCI compliance)
11. ❌ Never log a per-retry Stripe idempotency key (audit only the first)
12. ❌ Never use a per-retry idempotency key (use stable `(order_id, saga_step_name)`)
13. ❌ Never put card-shaped data in URLs (logged in proxy logs)
14. ❌ Never skip HMAC verification on inbound events
15. ❌ Never skip CSRF protection on state-changing endpoints

---

## 13. Cross-references

- **Risks being mitigated:** `RISK-REGISTER.md` (15 risks)
- **Architecture binding:** `architecture.md` §"Core Architectural Decisions" + ADR-INDEX.md
- **PCI scope enforcement:** `architecture-detail.md` §"Detail: ADR-23"
- **HMAC event signing:** `architecture-detail.md` §"Detail: ADR-20"
- **Vietnamese tax-invoice:** `architecture-detail.md` §"Detail: ADR-26" + `SPRINT-1-DEV-HANDBOOK.md`
- **Sprint tasks for security:** Stories 0.4 (CI scaffold includes lints), 3.1, 3.2, 3.3, 3.4, 3.5, 5.4, 9.2
