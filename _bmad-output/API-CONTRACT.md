---
audience: dev, frontend-dev, BFF-dev
project: side-project
date: 2026-07-06
how-to-use: REST endpoint reference. Pair with architecture.md §"Detail: ADR-09" (API style).
---

# API Contract — side-project

> **Architecture:** REST + BFF (per ADR-09). All services expose REST via BFF; clients never call services directly.
> **Base URL:** `/api/<service>/<version>/...`
> **Auth:** Service-account JWT (mTLS for service-to-service, httpOnly session cookie for browser)
> **Error format:** `{ "code": <int>, "status": "<UPPER_SNAKE>", "message": "<human readable>", "details": { ... } }`

---

## 1. Cross-cutting concerns

### Authentication

All endpoints (except health) require:
- **Browser → BFF**: session cookie (httpOnly, secure, sameSite=lax) with CSRF token
- **BFF → Service**: mTLS + service-account JWT (RS256, 5min lifetime)
- **Service → Service**: mTLS + JWT (audience: target service name)

### Rate limiting (per ADR-24)

- Per-IP: 100 req/min
- Per-IP+ASN: 500 req/min
- Per-card-fingerprint: 10 req/hour
- Per-BIN: 1000 req/hour (cross-user)
- Exceeding: 429 with `Retry-After` header

### Idempotency

For POST/PUT/DELETE endpoints, support `Idempotency-Key` header (max 64 chars). Same key + same body = same response. Different key = conflict (409).

### Pagination

Cursor-based for `?page=`:

```json
{
  "data": [...],
  "pagination": {
    "page": 1,
    "size": 20,
    "total": 1234,
    "has_next": true
  }
}
```

---

## 2. Catalog Service (`/api/catalog/v1`)

### `GET /products`

List products with pagination + filters.

**Query params:**
- `q` (string, optional) — search query (uses ES per-locale analyzer)
- `category` (string, optional)
- `brand` (string, optional)
- `min_price` (int, optional, in VND cents)
- `max_price` (int, optional)
- `in_stock` (bool, optional, default false)
- `locale` (string, optional, default `vi`) — `vi` | `en`
- `page` (int, optional, default 1)
- `size` (int, optional, default 20, max 100)

**Response 200:**
```json
{
  "data": [
    {
      "id": 12345,
      "uuid": "1234567890123456",
      "slug": "iphone-15-pro",
      "name": "iPhone 15 Pro",
      "brand": "Apple",
      "category": "phone",
      "price_list_cents": 30000000,
      "price_sale_cents": 28000000,
      "image_url": "https://cdn.example.com/products/iphone-15-pro.jpg",
      "variants": [
        {"sku": "abc123", "color": "natural", "storage_gb": 256}
      ]
    }
  ],
  "pagination": {"page": 1, "size": 20, "total": 1234, "has_next": true}
}
```

### `GET /products/{uuid}`

Get single product.

**Response 200:** Same as object in list, with full description field.

**Errors:**
- 404: product not found
- 410: product soft-deleted

### `POST /products` (admin only, per FR-7)

Create new product.

**Body:** `{ "name": "...", "slug": "...", "variants": [...] }`
**Errors:** 400 (validation), 409 (slug exists)

### `PUT /products/{uuid}` (admin only)

Update product. Writes outbox event `catalog.product.updated`.

### `DELETE /products/{uuid}` (admin only)

Soft-delete. Writes outbox event `catalog.product.deleted`. Enforced by `@SoftUk` (per ADR-05).

---

## 3. Inventory Service (`/api/inventory/v1`)

### `GET /on-hand`

Get current stock for a variant.

**Query params:**
- `variant_uuid` (long, required) — Snowflake ID
- `warehouse_id` (string, optional, default "default")

**Response 200:**
```json
{
  "variant_uuid": 12345,
  "warehouse_id": "default",
  "on_hand": 10,
  "reserved": 2,
  "available": 8
}
```

### `POST /reservations` (saga-internal, not public)

Reserve stock for a saga step. Called by checkout saga.

**Body:** `{ "variant_uuid": 12345, "warehouse_id": "default", "qty": 1, "saga_step_id": "checkout-uuid-payment", "ttl_minutes": 15 }`

**Response 201:** `{ "reservation_id": "uuid", "expires_at": "2026-07-06T11:45:00Z" }`
**Errors:**
- 409: insufficient stock
- 410: variant not found

### `DELETE /reservations/{reservation_id}` (saga-internal)

Release reservation. Called on saga compensation.

### `GET /reservations/{reservation_id}`

Check reservation status.

### `POST /adjustments` (admin only)

Manually adjust stock. Writes `inventory.adjusted` event.

---

## 4. Cart Service (`/api/cart/v1`)

### `GET /carts/{cart_id}`

Get cart contents.

**Response 200:**
```json
{
  "id": "cart-uuid",
  "user_id": null,  // or set if authenticated
  "anonymous": true,
  "lines": [
    {
      "variant_uuid": 12345,
      "sku": "abc123",
      "name": "iPhone 15 Pro",
      "qty": 1,
      "unit_price_cents": 30000000,
      "line_total_cents": 30000000,
      "added_at": "2026-07-06T10:30:00Z"
    }
  ],
  "totals": {
    "subtotal_cents": 30000000,
    "tax_cents": 3000000,
    "total_cents": 33000000,
    "currency": "VND"
  }
}
```

### `POST /carts/{cart_id}/lines`

Add line item. Idempotent on `(variant_uuid)` (updates qty if exists).

**Body:** `{ "variant_uuid": 12345, "qty": 1 }`
**Errors:**
- 409: max 100 lines per cart
- 410: variant not available

### `PATCH /carts/{cart_id}/lines/{line_id}`

Update qty. Optimistic concurrency via `If-Match: <cart.version>`.

### `DELETE /carts/{cart_id}/lines/{line_id}`

Remove line.

### `POST /carts/{cart_id}/merge` (called after login)

Merge anonymous cart into user's cart. Idempotent on `(guest_cart_id, user_id)`.

---

## 5. Checkout Service (`/api/checkout/v1`)

### `POST /checkouts`

Start checkout. Creates Checkout aggregate + initiates saga.

**Body:**
```json
{
  "cart_id": "cart-uuid",
  "shipping_address": {
    "recipient_name": "Nguyen Van A",
    "phone": "+84...",
    "province": "Ho Chi Minh",
    "district": "Quan 1",
    "commune": "Phuong Ben Nghe",
    "street": "123 Le Loi",
    "postal_code": "700000"
  },
  "billing_same_as_shipping": true
}
```

**Response 201:**
```json
{
  "id": "checkout-uuid",
  "state": "CREATED",
  "total_cents": 33000000,
  "stripe_client_secret": "pi_xxx_secret_xxx",
  "expires_at": "2026-07-06T11:45:00Z"
}
```

### `GET /checkouts/{id}`

Get current state. Polled by frontend.

### `POST /checkouts/{id}/cancel` (user action)

Cancel checkout. Triggers saga compensation.

---

## 6. Payment Service (`/api/payment/v1`)

### `POST /payments`

Create payment (usually called by checkout saga, not directly by BFF).

**Body:** `{ "checkout_id": "...", "amount_cents": 33000000, "currency": "VND" }`

**Response 201:** `{ "payment_id": "...", "stripe_payment_intent_id": "pi_xxx" }`

### `GET /payments/{id}`

Get payment status.

### `POST /webhooks/stripe` (public, Stripe-only)

Receive Stripe webhook. Verifies signature, dedupes on `event.id`. Returns 200 quickly (async processing).

### `POST /refunds/{payment_id}` (admin only)

Issue refund. Uses cumulative-refund safety check (per FR-43 / DI-07).

---

## 7. Order Service (`/api/order/v1`)

### `GET /orders/{uuid}`

Get single order.

**Response 200:**
```json
{
  "id": "order-uuid",
  "state": "PAID",
  "state_transitions": [
    {"from": null, "to": "CREATED", "at": "...", "saga_step": "..."},
    {"from": "CREATED", "to": "STOCK_RESERVED", "at": "..."},
    ...
  ],
  "price_snapshot_cents": 33000000,
  "shipping_address": {...},
  "billing_address": {...},
  "lines": [...],
  "payment": {"payment_id": "...", "amount_cents": 33000000},
  "shipment": null
}
```

### `GET /orders` (user = self, staff = all)

List orders. User sees their own; staff sees all.

### `GET /orders/{uuid}/timeline`

Get the user-facing timeline (per FR-33).

**Response 200:**
```json
{
  "events": [
    {"at": "2026-07-06T10:30:00Z", "label": "Đã đặt hàng", "code": "ORDER_PLACED"},
    {"at": "2026-07-06T10:31:00Z", "label": "Đã thanh toán", "code": "PAYMENT_CAPTURED"},
    {"at": "2026-07-06T11:00:00Z", "label": "Đang đóng gói", "code": "PACKING"},
    ...
  ]
}
```

### `PATCH /orders/{uuid}` (user, before SHIPPED)

Edit address or cancel. Per FR-34 (30-min TTL after order placed).

### `POST /orders/{uuid}/cancel` (user, before SHIPPED)

Cancel order. Triggers saga compensation (Stripe refund + inventory release).

---

## 8. Fulfillment Service (`/api/fulfillment/v1`)

### `POST /shipments` (saga-internal, not public)

Create shipment. Called by saga after PAID state.

### `GET /shipments/{id}/tracking`

Get tracking info.

**Response 200:**
```json
{
  "id": "shipment-uuid",
  "carrier": "GHN",
  "tracking_number": "GHN123456789",
  "events": [
    {"at": "...", "status": "DISPATCHED", "location": "HCM Warehouse"},
    {"at": "...", "status": "IN_TRANSIT", "location": "Binh Duong Hub"},
    {"at": "...", "status": "DELIVERED", "location": "Quan 1, HCM"}
  ],
  "estimated_delivery_at": "2026-07-08T10:00:00Z"
}
```

### `POST /webhooks/{carrier}` (public, per-carrier)

Receive carrier webhook. Updates shipment state. Returns 200 quickly.

### `POST /shipments/{id}/deliver` (admin only, manual)

Mark shipment delivered (when carrier webhook is unavailable).

---

## 9. Returns Service (`/api/returns/v1`)

### `POST /returns` (user)

Create RMA. Default = exchange-first per FR-41.

**Body:**
```json
{
  "order_id": "...",
  "items": [{"variant_uuid": 12345, "qty": 1, "reason": "defective"}],
  "evidence_photos": ["url1", "url2"]
}
```

**Response 201:** `{ "id": "return-uuid", "suggested_action": "EXCHANGE" }`

### `GET /returns/{id}`

Get RMA status.

### `POST /returns/{id}/refund` (user, opts out of exchange)

Issue refund. Cumulative-refund safety check (per FR-43).

**Body:** `{ "confirm": true }`
**Response 200:** `{ "refund_id": "...", "amount_cents": 30000000 }`
**Errors:**
- 409: cumulative refund would exceed payment

---

## 10. Customer Service (`/api/customer/v1`)

### `POST /auth/register`

Register new user.

**Body:** `{ "email": "...", "password": "..." }`
**Response 201:** `{ "user_id": "...", "verification_required": true }`

### `POST /auth/login`

Login. Sets httpOnly session cookie. Implements account lockout after 5 failures (per FR-76 / AT-02).

**Body:** `{ "email": "...", "password": "...", "mfa_token": "..." (optional) }`

**Response 200:** `{ "user_id": "...", "role": "customer", "mfa_required": false }`

**Errors:**
- 401: bad credentials
- 423: account locked

### `POST /auth/logout`

Logout. Clears session cookie.

### `GET /me`

Get current user profile.

### `GET /me/export` (per FR-46 / LC-01)

PDPD data export. Returns JSON/ZIP of all customer data.

### `POST /me/forget` (per FR-49)

R2F. Hard-deletes PII, anonymizes order history.

### `GET /me/addresses`

List addresses.

### `POST /me/addresses`

Add address. Uses util's ProvinceDto/DistrictDto/CommuneDto.

---

## 11. Search Service (`/api/search/v1`)

### `GET /products`

Same shape as `Catalog Service /products` but goes through ES (per-locale index per architecture §"Detail: ADR-04"). Different latency target (FR-52: p99 < 300ms).

---

## 12. Notification Service (`/api/notification/v1`)

### `GET /preferences`

Get user notification preferences (per FR-58).

### `PUT /preferences`

Update preferences.

**Body:**
```json
{
  "email": {"order_placed": true, "order_shipped": true, ...},
  "push": {"order_placed": true, ...},
  "sms": {"order_placed": false, ...},
  "quiet_hours": {"start": "22:00", "end": "07:00", "timezone": "Asia/Ho_Chi_Minh"}
}
```

---

## 13. Invoice Service (`/api/invoice/v1`)

### `GET /invoices/{id}`

Get invoice (PDF + metadata).

### `GET /invoices/{id}/pdf`

Download invoice PDF (Jasper-rendered with Vietnamese fonts).

---

## 14. Admin Service (`/api/admin/v1`)

### `GET /audit-trail`

Get audit trail (filtered by date, actor, resource).

**Query params:** `actor_id`, `resource_type`, `resource_id`, `from`, `to`, `page`, `size`

**Response 200:** Array of audit entries with before/after state.

### `POST /approvals/{change_id}/approve` (admin only)

Approve a high-impact change (per FR-64).

### `POST /approvals/{change_id}/reject` (admin only)

Reject.

---

## 15. Error format (uniform across all services)

```json
{
  "code": 400,
  "status": "BAD_REQUEST",
  "message": "Validation failed: cart must not be empty",
  "details": {
    "field": "cart_id",
    "reason": "required"
  },
  "trace_id": "abc123def456",  // OTel traceId for debugging
  "span_id": "789ghi",
  "timestamp": "2026-07-06T11:30:00Z"
}
```

### Status code mapping

| HTTP | Service status | Use case |
|---|---|---|
| 200 | OK | Success (with data) |
| 201 | CREATED | Resource created |
| 204 | NO_CONTENT | Success (no body) |
| 400 | BAD_REQUEST | Validation error |
| 401 | UNAUTHORIZED | Missing or bad auth |
| 403 | FORBIDDEN | Auth ok but no permission |
| 404 | NOT_FOUND | Resource doesn't exist |
| 409 | CONFLICT | Idempotency key conflict, unique constraint, etc. |
| 410 | GONE | Soft-deleted |
| 422 | UNPROCESSABLE_ENTITY | Business rule violation |
| 429 | TOO_MANY_REQUESTS | Rate-limited |
| 500 | INTERNAL_SERVER_ERROR | Unhandled exception |
| 502 | BAD_GATEWAY | Downstream service failed |
| 503 | SERVICE_UNAVAILABLE | Service in maintenance |

---

## 16. BFF responsibilities (per ADR-09)

| Concern | Service-level | BFF-level |
|---|---|---|
| Authentication | (user identity) | Session translation, token rotation |
| Rate limiting | (per-ADR-24) | IP-based edge limits |
| Aggregation | (per-service events) | Combine responses from multiple services |
| Caching | (DB-side) | Edge cache (CDN, per-customer) |
| Authorization | (RBAC server-side) | Pass JWT claims; client doesn't filter |
| Compression | (gzip) | gzip + cache-control headers |
| Logging | (structured JSON) | MDC propagation; OTel trace context |
| CORS | (none) | Origin allowlist |
| Health checks | (Spring Actuator) | Aggregated health endpoint |

---

## 17. OpenAPI spec

Each service's OpenAPI spec lives in `services/<x>/src/main/resources/openapi.yaml` (to be generated from `springdoc-openapi`). BFF OpenAPI is composed from the union of downstream services.

For local dev, view Swagger UI at:
- Catalog: http://localhost:8080/swagger-ui
- Inventory: http://localhost:8081/swagger-ui
- ...

---

## 18. Cross-references

- **Architecture API style (ADR-09):** `architecture.md` §"Detail: ADR-09"
- **Auth model:** `SECURITY-MODEL.md` §2
- **Rate-limit keys:** `SECURITY-MODEL.md` §5
- **Domain boundaries:** `PROBLEM-DOMAINS-MAP.md`
- **Per-service file structure:** `architecture.md` §"Project Structure & Boundaries"
- **Per-sprint backend work:** `SPRINT-1-DEV-HANDBOOK.md`
- **Frontend integration:** `FRONTEND-HANDBOOK.md`
- **BFF implementation:** `architecture.md` §"Service Boundaries (intra-Modulith)"
