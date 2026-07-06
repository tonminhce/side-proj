---
audience: all agents
project: side-project
date: 2026-07-06
how-to-use: project-wide conventions. Single source for naming + style. Cross-refs to source-of-truth docs.
---

# Conventions — side-project

> **Single source of truth** for project-wide naming, file structure, and code style.
> **When in doubt:** follow this doc. If a conflict exists between this and another doc, the more specific doc wins (e.g., SECURITY-MODEL for security matters).

---

## 1. File / directory naming

### General rules

- **All file/directory names:** `kebab-case` (lowercase with hyphens)
- **Examples:** `add-user-form.tsx`, `inventory-service.md`, `payment-webhook-handler.ts`

### Special files

| File | Convention | Why |
|---|---|---|
| `README.md` | UPPERCASE | Industry standard |
| `Dockerfile` | UPPERCASE | Industry standard |
| `Makefile` | UPPERCASE | Industry standard |
| `pom.xml` | lowercase | Maven convention |
| `package.json` | lowercase | npm convention |
| `tsconfig.json` | lowercase | TypeScript convention |
| `.env.example` | lowercase | Convention (NOT `.env` which would be ignored by git) |
| `*.md` | `kebab-case.md` | Per file rules |

### Quickref docs (this project)

Pattern: `LOWER-CASE-NAME.md` (e.g., `AGENT-ONBOARDING.md`, `RISK-REGISTER.md`).

---

## 2. Java naming

### Classes

- **PascalCase** (upper camel case)
- Examples: `CheckoutService`, `ProductAggregate`, `StripeWebhookHandler`
- No abbreviations (use `ProductRepository`, not `ProdRepo`)

### Interfaces

- **PascalCase** (no `I` prefix per Java convention)
- Examples: `Saga`, `OutboxPublisher`, `ShipmentService`
- Exception: `Iterable<T>` (JDK convention)

### Methods + Variables

- **camelCase**
- Examples: `reserveStock`, `outbox.append`, `catalogService.getProductBySku`
- Boolean: `isActive`, `hasError`, `shouldRetry` (prefix with `is/has/should`)

### Constants

- **UPPER_SNAKE_CASE**
- Examples: `MAX_RESERVATION_TTL_MINUTES`, `DEFAULT_CURRENCY_CODE`

### Packages

- **lowercase, dot-separated**
- Pattern: `vn.vnpt.<service>.<layer>`
- Examples: `vn.vnpt.catalog.api`, `vn.vnpt.inventory.domain`, `vn.vnpt.payment.application`

### Java modules (Maven)

- **kebab-case** directory names
- Pattern: `services/<service-name>/` (e.g., `services/catalog/`, `services/payment/`)
- pom.xml artifact: `<groupId>vn.vnpt</groupId> <artifactId><service-name></artifactId>`

---

## 3. TypeScript / Next.js naming

### Components

- **PascalCase** (function components)
- Examples: `CartItem`, `CheckoutForm`, `ProductCard`
- One component per file: `CartItem.tsx` defines `CartItem`

### Hooks

- **camelCase**, prefix `use`
- Examples: `useCart`, `useStripePayment`, `useVietnameseAddress`

### Utilities / functions

- **camelCase**
- Examples: `formatVnd`, `parseAddress`, `validateEmail`

### Constants

- **UPPER_SNAKE_CASE**
- Examples: `MAX_LINE_ITEMS`, `DEFAULT_LOCALE`

### Types / Interfaces

- **PascalCase** (no `I` prefix per modern TS convention)
- Examples: `CartLine`, `Address`, `User`

### Files

- Components: `PascalCase.tsx` (e.g., `CartItem.tsx`)
- Hooks: `use-thing.ts` (e.g., `use-cart.ts`)
- Utilities: `kebab-case.ts` (e.g., `format-vnd.ts`)
- Types: `kebab-case.ts` (e.g., `cart-types.ts`)

---

## 4. Java / Spring code style

### Imports

- Alphabetical within group
- Static imports last
- No wildcard imports (`*`); use explicit

```java
// ✅ RIGHT
import java.util.List;
import java.util.Map;
import vn.vnpt.util.SnowflakeIdGenerator;
```

### Indentation

- 4 spaces (not tabs)

### Line length

- 120 chars max (typical Spring Boot)
- Break long lines at logical points

### Braces

- Allman style (braces on own line) — per Spring convention
- OR K&R (Egyptian) — per Google style
- **Use Spring convention:** K&R (braces on same line for control flow)

```java
// ✅ RIGHT (K&R)
if (x > 0) {
    doSomething();
}

// Method declarations: braces on next line
public void method() 
{
    doSomething();
}
```

### Methods

- Public methods first, then protected, then private
- Constructors first, then static factory methods, then instance methods

### Comments

- Use `//` for line comments, `/* */` for block comments
- Javadoc for public methods
- Don't comment the obvious; comment the *why*

---

## 5. TypeScript / Next.js code style

### Imports

- Alphabetical
- Group: external / internal / relative

```typescript
// External
import { useState } from 'react';
import { loadStripe } from '@stripe/stripe-js';

// Internal
import { formatVnd } from '@/lib/format-vnd';
import type { Cart } from '@/types/cart-types';
```

### Formatting

- **Prettier** auto-formats (run on save)
- 2-space indentation
- 100 chars line length (Next.js default)

### Components

- Functional components preferred
- Props destructured at function signature
- Hooks at top, in order

```typescript
// ✅ RIGHT
export function ProductCard({ product, onAdd }: ProductCardProps) {
    const [isLoading, setIsLoading] = useState(false);
    
    const handleClick = () => {
        setIsLoading(true);
        onAdd(product);
    };
    
    return (
        <Card>
            <Button onClick={handleClick} disabled={isLoading}>
                Add to cart
            </Button>
        </Card>
    );
}
```

---

## 6. Database naming

### Tables (per `DATA-MODEL.md`)

- **snake_case**, plural
- Examples: `users`, `cart_lines`, `inventory_ledger`, `order_state_transition`
- Junction tables: `<table1>_<table2>` (alphabetical) or `<table1>_to_<table2>`

### Columns (per `DATA-MODEL.md`)

- **snake_case**
- Examples: `user_id`, `created_at`, `is_active`, `price_list_cents`
- Foreign keys: `<referenced_table_singular>_id`
- Booleans: `is_<adjective>` or `has_<noun>`

### Indexes (per `DATA-MODEL.md`)

- Pattern: `idx_<table>_<column>` or `idx_<table>_<col1>_<col2>`
- Unique: `uq_<table>_<column>`

### Constraints

- Primary key: `pk_<table>` (or just `id`)
- Foreign key: `fk_<table>_<referenced_table>`
- Check: `chk_<table>_<column>`

---

## 7. API conventions

### REST endpoints (per `API-CONTRACT.md`)

- Plural nouns, kebab-case
- Pattern: `GET /api/<service>/<version>/<resource>`
- Examples: `GET /api/catalog/v1/products`, `POST /api/cart/v1/lines`

### HTTP methods

- `GET` — read
- `POST` — create (idempotency-key required)
- `PUT` — full update
- `PATCH` — partial update
- `DELETE` — soft-delete (per ADR-05, hard-delete forbidden)

### HTTP status codes (per `API-CONTRACT.md` §15)

- `200` OK
- `201` Created
- `204` No Content
- `400` Bad Request (validation)
- `401` Unauthorized
- `403` Forbidden
- `404` Not Found
- `409` Conflict (idempotency, unique)
- `410` Gone (soft-deleted)
- `422` Unprocessable Entity (business rule)
- `429` Too Many Requests (rate-limited)
- `500` Internal Server Error
- `503` Service Unavailable

### Error response (per `API-CONTRACT.md` §15)

```json
{
  "code": 400,
  "status": "BAD_REQUEST",
  "message": "Validation failed: cart must not be empty",
  "details": { "field": "cart_id", "reason": "required" },
  "trace_id": "abc123def456"
}
```

---

## 8. Git conventions

### Branch names

- Pattern: `<type>/<scope>-<short-desc>`
- Examples:
  - `feat/catalog-per-locale-index-bootstrap`
  - `fix/payment-double-capture-idempotency`
  - `refactor/inventory-ledger-structure`
  - `docs/update-architecture-cycle-5`
  - `chore/upgrade-stripe-sdk-12.34.5`
  - `test/chaos-r-04-kafka-broker-kill`

### Commit messages (per `CONTRIBUTING.md` + `CHANGELOG.md`)

- Format: `<type>(<scope>): <description>`
- Examples:
  - `feat(catalog): add per-locale ES index bootstrap [story 6.1]`
  - `fix(payment): solve DI-02 double-capture [R-03] [story 3.1]`
  - `refactor(architecture): split into main + detail [cycle 5]`

### Commit body

- 1-2 lines explaining *why* (not *what* — git diff shows *what*)
- 72 char limit for the first line

### Pull request

- One feature per PR
- Use `CONTRIBUTING.md` template
- 1+ reviewer (2+ for arch / security / schema)
- Squash-merge default

### Git tags

- Pattern: `v<MAJOR>.<MINOR>.<PATCH>` (e.g., `v1.0.0`, `v1.0.1`, `v0.9.0-beta.1`)
- Annotated tags (per `RELEASE-PROCESS.md` §6)

---

## 9. Event / topic naming (Kafka, per `KAFKA-TOPIC-LIFECYCLE.md`)

### Topics

- Pattern: `<aggregate>.<lifecycle-event>` (kebab-case)
- Examples: `orders.placed`, `payment.captured`, `inventory.reserved`

### Event types (Avro)

- PascalCase
- Examples: `OrderPlaced`, `PaymentCaptured`, `InventoryReserved`

### Field names

- snake_case in Avro
- camelCase in Java POJOs (Jackson auto-converts)

---

## 10. Metrics / logging (per `METRICS-DICTIONARY.md`)

### Metric names (Micrometer)

- Pattern: `<service>.<entity>.<action>_<unit>` (per `METRICS-DICTIONARY.md` §1)
- Examples: `catalog.product.created_total`, `payment.captured.duration_seconds`

### Labels

- snake_case
- Low-cardinality only (per `METRICS-DICTIONARY.md` §14)
- Examples: `outcome` (`success` | `failure`), `locale` (`vi` | `en`), `variant_uuid` (DON'T do this — high cardinality!)

### Log lines

- JSON format (per `OBSERVABILITY-RUNBOOK.md`)
- Always include `traceId` + `spanId`
- Never include PAN-shaped data
- Use structured fields, not concatenated strings

---

## 11. Testing conventions (per `INTEGRATION-TEST-CHEATSHEET.md`)

### Test file names

- Unit: `<Class>Test.java` (e.g., `ProductTest.java`)
- Integration: `<Class>IT.java` (e.g., `ProductServiceIT.java`)
- Failsafe plugin picks up `*IT`; Surefire picks up `*Test`

### Test method names

- Pattern: `methodName_condition_expectedBehavior` (BDD style)
- Examples:
  - `reserveStock_withInsufficientStock_throwsInsufficientStockException`
  - `login_withValidCredentials_returnsSessionToken`

### Test fixtures

- Per service: `<Service>TestDataFactory`
- Vietnamese names: real-ish but not real
- No real PII

---

## 12. Feature flag naming (per `FEATURE-FLAGS.md`)

- Pattern: `ff.<scope>.<feature>.<state>`
- Examples: `ff.checkout.use-saga-v2`, `ff.payment.enable-3ds-stepup`

---

## 13. Helm / K8s (per `DEVOPS-RUNBOOK.md`)

### Helm chart names

- Pattern: `helm/<service>/Chart.yaml`
- Examples: `helm/catalog/`, `helm/payment/`

### K8s resource names

- `kebab-case`, lowercase
- Pattern: `<service>-<env>` (e.g., `catalog-prod`, `payment-staging`)
- Deployments: `<service>-<env>-deployment`
- Services: `<service>-<env>-service`
- ConfigMaps: `<service>-<env>-config`
- Secrets: `<service>-<env>-secrets`

### Labels

- Standard K8s labels: `app.kubernetes.io/name`, `app.kubernetes.io/instance`, etc.
- Plus our own: `service: <name>`, `env: <env>`, `version: <tag>`

---

## 14. Documentation (per `AGENT-ONBOARDING.md`)

### Quickref docs

- One audience per doc
- Frontmatter: `audience:`, `how-to-use:`
- Cross-refs at bottom

### Canonical docs

- One workflow = one file
- Frontmatter: `title:`, `status:`, `reviewCycle:`
- Companion pattern (PRD + addendum; arch + detail)

### Diagrams

- Use Mermaid (text-based, version-controllable)
- Per `ARCHITECTURE-DIAGRAMS.md` (when needed)

---

## 15. Anti-patterns

### ❌ Anti-pattern 1: Mixed case in DB columns

```sql
-- ❌ WRONG
CREATE TABLE Users (
    id BIGINT,
    userId BIGINT,  -- mixed case
    Created_At TIMESTAMP
);

-- ✅ RIGHT
CREATE TABLE users (
    id BIGINT,
    user_id BIGINT,
    created_at TIMESTAMP
);
```

### ❌ Anti-pattern 2: PAN in any log

```java
// ❌ WRONG
log.info("User paid with card {}", request.getCardNumber());
// PCI scope violation (per R-15)

// ✅ RIGHT
log.info("Payment captured for user {}", userId);
```

### ❌ Anti-pattern 3: Skipping a story in the AC

```markdown
<!-- ❌ WRONG: -->
### Story 1.6: Reservation
- **Given** ... **When** ... **Then** ...   <!-- no **And** for edge cases -->

<!-- ✅ RIGHT: -->
### Story 1.6: Reservation
- **Given** ... **When** ... **Then** ...
- **And** on insufficient stock, throws `InsufficientStockException` with detail message
- **And** reservation has TTL of 15 minutes
```

### ❌ Anti-pattern 4: Mixing layers

```java
// ❌ WRONG: API layer with business logic
@RestController
public class CheckoutController {
    @PostMapping("/checkout")
    public CheckoutResult start(@RequestBody Cart cart) {
        // Business logic here ← should be in service layer
        if (cart.lines.isEmpty()) throw new IllegalArgumentException();
        return checkoutService.start(cart);
    }
}

// ✅ RIGHT: API layer delegates to service
@RestController
public class CheckoutController {
    @PostMapping("/checkout")
    public CheckoutResult start(@RequestBody Cart cart) {
        return checkoutService.start(cart);  // service throws if invalid
    }
}
```

### ❌ Anti-pattern 5: Not committing the schema

```bash
# ❌ WRONG
# Created a new Flyway migration but didn't commit
git add src/main/java/.../SomeService.java
git commit -m "Add new service"
# Migration file is in working tree, not committed

# ✅ RIGHT
git add src/main/java/.../SomeService.java src/main/resources/db/migration/V002__add_table.sql
git commit -m "Add new service + migration"
```

---

## 16. Cross-references

- **Architecture (canonical):** `architecture.md` + `architecture-detail.md`
- **API contract:** `API-CONTRACT.md`
- **Data model:** `DATA-MODEL.md`
- **Frontend handbook:** `FRONTEND-HANDBOOK.md`
- **PR conventions:** `CONTRIBUTING.md`
- **Test patterns:** `INTEGRATION-TEST-CHEATSHEET.md`
- **Test data:** `TEST-DATA-MANAGEMENT.md`
- **Security model:** `SECURITY-MODEL.md`
- **Kafka topic lifecycle:** `KAFKA-TOPIC-LIFECYCLE.md`
- **Metrics dictionary:** `METRICS-DICTIONARY.md`
- **Feature flags:** `FEATURE-FLAGS.md`
- **Caching strategy:** `CACHING-STRATEGY.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
- **Changelog:** `CHANGELOG.md`
- **Release process:** `RELEASE-PROCESS.md`
- **Operational runbook:** `DEVOPS-RUNBOOK.md`
- **Disaster recovery:** `DISASTER-RECOVERY.md`
- **On-call roster:** `ON-CALL-ROSTER.md`
- **Capacity planning:** `CAPACITY-PLANNING.md`
- **A11y:** `A11Y-CHECKLIST.md`
- **Compliance audit:** `COMPLIANCE-AUDIT-CHECKLIST.md`
- **Risk register:** `RISK-REGISTER.md`
- **Architecture diagrams:** `ARCHITECTURE-DIAGRAMS.md`
- **Agent interaction:** `AGENT-INTERACTION.md`
