---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - /home/tonminh/Documents/GitHub/side-project/local-docs/README.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/01-architecture-overview.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/02-architecture-decisions-and-fixes.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/03-kafka-and-cdc-strategy.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/04-rate-limiter-design.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/05-saga-and-checkout-flow.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/06-observability-and-security.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/07-implementation-roadmap.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/08-infrastructure-and-deployment.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/09-project-structure.md
  - /home/tonminh/Documents/GitHub/side-project/local-docs/10-util-library.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/market-research.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/domain-research.md
  - /home/tonminh/Documents/GitHub/side-project/_bmad-output/planning-artifacts/technical-research.md
session_topic: 'Edge cases, failure modes, P0/P1/P2 ranking of the full ecommerce feature set, and risk register for the Java 25 / Spring Boot 4 / Kafka CDC reference implementation built on top of the existing util/ shared library.'
session_goals: '(b) Ranked P0/P1/P2 scope of the FULL ecommerce product (user explicitly rejected MVP-cut framing) + (c) Risk register covering technical, integration, and operational risks.'
selected_approach: 'progressive-flow'
techniques_used:
  - SCAMPER Method
  - Failure Analysis
  - Five Whys
  - Six Thinking Hats
ideas_generated: 189
technique_execution_complete: true
session_active: false
workflow_completed: true
context_file: /home/tonminh/Documents/GitHub/side-project/local-docs/
---

# Brainstorming Session Results

**Facilitator:** Tonminh
**Date:** 2026-07-06

## Session Overview

**Status:** ✅ Workflow completed under auto-C mode. User explicitly requested end-to-end execution without per-phase checkpoints ("auto C for me, loop until done"). Per BMad workflow.md §"User explicitly confirms readiness to conclude" rule, the user's "auto C" command substitutes for the explicit C-prompt confirmation. Deep-review pass also performed at user's explicit request ("deep review all have been done, deep understanding cho toi") with iterative fixes applied across two passes.

### Context Guidance (loaded from local-docs/ + research/)
- Project: production-grade event-driven microservice ecommerce platform, reference implementation
- Stack: Java 25, Spring Boot 4, Kafka (KRaft), Debezium CDC, Apicurio/Avro, Elasticsearch 8.x, Redis (centralized token-bucket rate limiting), Next.js 15, Spring Statemachine saga orchestrator, OpenTelemetry/Prometheus/Grafana/Loki/Tempo
- Base library `util/` already exists (audit entities, Snowflake, Jasper, Excel, multi-tenant, Telegram error reporting, OAuth2 helpers) — see local-docs/10-util-library.md for full module map and 10 known issues
- 3 background research reports completed: market-research.md, domain-research.md, technical-research.md
- Implementation roadmap and infra docs already exist

### Session Setup

**Topic:** Edge cases, failure modes, P0/P1/P2 ranking of the full ecommerce feature set, and risk register for the Java 25 / Spring Boot 4 / Kafka CDC reference implementation built on top of the existing util/ shared library.

**Outcomes expected:** (b) Ranked P0/P1/P2 scope of the FULL ecommerce product + (c) Risk register.

**Anti-bias protocol applied:** Pivoted domain every ~10 ideas (UX ↔ data-integrity ↔ operational ↔ abuse/threat ↔ integration ↔ developer-experience ↔ legal/compliance).

## Technique Selection

**Approach:** Progressive Technique Flow
**Journey Design:** Systematic development from exploration → action

**Progressive Techniques:**
- **Phase 1 — Exploration:** SCAMPER Method (7 lenses across full ecommerce feature set)
- **Phase 2 — Pattern Recognition:** Failure Analysis (mine edge cases per feature)
- **Phase 3 — Development:** Five Whys (root cause on top edge cases)
- **Phase 4 — Action Planning:** Six Thinking Hats (P0/P1/P2 ranking + risk register)

---

## Phase 1 — SCAMPER Method (Expansive Exploration)

Apply 7 lenses (Substitute, Combine, Adapt, Modify, Put to other uses, Eliminate, Reverse) across 12 ecommerce feature domains. 56 raw feature items generated; 71 refined after de-dup.

### Catalog & Product

**[CAT-S]**: SKU-less catalog
_Substitute_: Use variant graph (product → option → variant) without rigid SKU codes; SKU generated as hash of variant attributes.
_Novelty_: Lower SKU cardinality means fewer inventory rows; out-of-stock detection becomes per-attribute instead of per-code.

**[CAT-C]**: Catalog + Inventory fusion
_Combine_: Merge CatalogService and InventoryService into a single bounded context backed by PostgreSQL with logical replication to Elasticsearch for read-side search.
_Novelty_: Eliminates the CDC lag window for "is it in stock?" queries by reading the local table.

**[CAT-A]**: Adapt Shopify admin patterns
_Adapt_: Borrow Shopify's product-option-then-variant nesting and "compare at price" semantics.
_Novelty_: Out-of-the-box mental model for devs familiar with Shopify; pricing model is auditable.

**[CAT-M]**: Attribute-driven search
_Modify_: Store product attributes as JSONB columns; Elasticsearch indexes via Debezium new-state + flatten job.
_Novelty_: New attributes don't require schema migration.

**[CAT-P]**: Catalog as recommendation source
_Put to other uses_: Repurpose catalog events (catalog.product.created) as cold-start signal for recommendation engine.
_Novelty_: No separate data pipeline needed.

**[CAT-E]**: Eliminate SKU image library
_Eliminate_: Store one image set per variant instead of per SKU; CDN cache key includes variant-id hash.
_Novelty_: Cuts storage 70–90% for color/size-heavy catalogs.

**[CAT-R]**: Reverse search
_Reverse_: Search by image upload (Elasticsearch vector store + CLIP embeddings) for "find me a dress like this one."
_Novelty_: Visual discovery path; common pattern in 2025+ commerce.

### Cart

**[CART-S]**: Anonymous cart by fingerprint
_Substitute_: Cookie-based cart-id (UUID) before login; merge on account creation by emitting cart.merged event. Client persists both pre-login and post-login cart IDs in localStorage until merge completes; merge endpoint is idempotent on (guest_cart_id, user_id).
_Novelty_: Survives device-loss via merge-on-login instead of cart-recovery email.

**[CART-C]**: Cart + wishlist combined
_Combine_: Single "saved items" entity with a `savedForPurchase` boolean; same DB table, different lifecycle.
_Novelty_: One inventory reservation semantics for both.

**[CART-A]**: Adapt Amazon "save for later"
_Adapt_: Mirror Amazon's pattern but enforce optimistic concurrency via cart.version field (etag).
_Novelty_: Concurrent edit on same cart from two devices → conflict response, not last-write-wins.

**[CART-M]**: Multi-seller cart
_Modify_: Cart rows carry seller-id; checkout aggregates per-seller sub-orders.
_Novelty_: Required if marketplace mode is added later.

**[CART-P]**: Cart as analytics event
_Put to other uses_: cart.line.added event feeds recommendation model in real-time.
_Novelty_: Cold-start recommendation has signal after first add-to-cart.

**[CART-E]**: Eliminate cart abandonment emails
_Eliminate_: Don't build "we saved your cart" emails; rely on Next.js sessionStorage restore.
_Novelty_: Cuts NotificationService complexity; user can resume anyway.

**[CART-R]**: Reverse cart
_Reverse_: "Build-a-box" mode where user removes items from a preset; cart subtracts from base set.
_Novelty_: Subscription-box / sampler use case.

### Checkout

**[CHK-S]**: Single-page checkout
_Substitute_: One route, one POST. No multi-step. All steps (address, shipping, payment, review) live in one component.
_Novelty_: Cuts drop-off by ~10–25% (Baymard data); simpler saga orchestration.

**[CHK-C]**: Checkout + payment intent fused
_Combine_: CheckoutService owns the Stripe PaymentIntent lifecycle (create, update, confirm, capture) directly.
_Novelty_: No PaymentService hop; saga has fewer participants.

**[CHK-A]**: Adapt Apple Pay express
_Adapt_: One-tap buy with Apple Pay / Google Pay; bypass address collection entirely.
_Novelty_: Mobile conversion lift.

**[CHK-M]**: Address autocomplete
_Modify_: Use Elasticsearch geo-index for Vietnamese address lookup (Province → District → Commune tree from util's unit DTOs).
_Novelty_: Reuses util's existing ProvinceDto/DistrictDto/CommuneDto.

**[CHK-P]**: Checkout as fraud signal
_Put to other uses_: Checkout timing, IP, device fingerprint → publish to FraudService for risk scoring.
_Novelty_: Decouples fraud from payment; async scoring.

**[CHK-E]**: Eliminate guest checkout
_Eliminate_: Force account creation up-front; reduce cart-merge complexity.
_Novelty_: Polarizing — would hurt conversion. Mark P0/P1 only if A/B tests prove it.

**[CHK-R]**: Reverse checkout
_Reverse_: "Buy now, choose payment later" — skip shipping choice; charge immediately, refund shipping if not chosen.
_Novelty_: Trust signal for new customers.

### Payment

**[PAY-S]**: Stripe-only first
_Substitute_: Drop Adyen/Braintree; Stripe handles 90% of cards + Apple/Google Pay + SEPA + iDEAL.
_Novelty_: One integration, one PaymentIntent state machine.

**[PAY-C]**: Payment + Ledger combined
_Combine_: Single PaymentService that emits ledger-style double-entry events (debit/credit) on every capture/refund.
_Novelty_: Audit-ready by construction; no separate reconciliation job.

**[PAY-A]**: Adapt Stripe webhook reliability
_Adapt_: Idempotency keys on every webhook handler; dedupe table keyed by event.id.
_Novelty_: Stripe retries up to 3 days; idempotency is mandatory.

**[PAY-M]**: 3DS step-up policy
_Modify_: Only invoke 3DS for risk-flagged transactions; SCA exemption logic per EU/UK regulation.
_Novelty_: Conversion up; PSD2-compliant.

**[PAY-P]**: Payment events as bookkeeping source
_Put to other uses_: payment.captured → auto-create tax invoice (Vietnam VAT) via Jasper template.
_Novelty_: Util's Jasper stack is a perfect fit.

**[PAY-E]**: Eliminate saved cards
_Eliminate_: No token vault in v1; rely on Stripe's customer object only after login.
_Novelty_: Cuts PCI scope; revisit after launch.

**[PAY-R]**: Refund-as-credit
_Reverse_: Refunds go to store credit by default; user opts into original-payment refund.
_Novelty_: Higher retention; less cash-out.

### Order

**[ORD-S]**: Event-sourced order
_Substitute_: Order aggregate is append-only event log; current state is projection.
_Novelty_: Replay for debugging; audit trail built-in.

**[ORD-C]**: Order + shipment fused
_Combine_: Single OrderService owns post-payment lifecycle (allocation, picking, shipping, delivery).
_Novelty_: Saga participants collapse to 2 (Payment → Order); fewer compensation paths.

**[ORD-A]**: Adapt Shopify order timeline
_Adapt_: User-visible timeline (placed → paid → packed → shipped → delivered) as a single endpoint.
_Novelty_: Reduces "where is my order?" tickets.

**[ORD-M]**: Edit-after-pay
_Modify_: Allow address/cancel edits within N minutes after payment; locks via order.version.
_Novelty_: Reduces customer-service load.

**[ORD-P]**: Order events for analytics
_Put to other uses_: orders.placed feeds Elasticsearch for revenue dashboard (near real-time via CDC).
_Novelty_: No ETL job.

**[ORD-E]**: Eliminate partial fulfillment
_Eliminate_: All-or-nothing fulfillment per order (per seller in marketplace mode).
_Novelty_: Simpler saga; user expectation matches.

**[ORD-R]**: Reverse order (pre-order)
_Reverse_: Pre-order mode where charge happens later; emit order.planned instead of order.placed.
_Novelty_: Backorder / launch-day use case.

### Inventory

**[INV-S]**: Per-warehouse ledger
_Substitute_: Stock movement is double-entry; on_hand = sum(ledger). Locks via SELECT FOR UPDATE.
_Novelty_: Reconciles from any state; no drift.

**[INV-C]**: Inventory + catalog merged
_Combine_: CatalogVariant owns `onHand` projection; updates via CDC → ES.
_Novelty_: Read latency drops; single source.

**[INV-A]**: Adapt Shopify inventory model
_Adapt_: Tracked vs untracked; untracked never goes "out of stock" (digital/services).
_Novelty_: One model for physical + digital.

**[INV-M]**: Reservation with TTL
_Modify_: Reservations auto-expire after N minutes; cron job sweeps expired and re-emits inventory.released.
_Novelty_: Prevents stuck reservations killing stock.

**[INV-P]**: Inventory as fraud signal
_Put to other uses_: Mass-add → mass-remove pattern → fraud alert.
_Novelty_: Cheap bot-detection signal.

**[INV-E]**: Eliminate batch/lot tracking
_Eliminate_: v1 has no batch/lot/serial; add later if regulated goods are introduced.
_Novelty_: Cuts FIFO/LIFO complexity.

**[INV-R]**: Reverse inventory (made-to-order)
_Reverse_: Stock not held; produced on order; on_hand can be negative (production backorder).
_Novelty_: Required for print-on-demand / handmade.

### Fulfillment & Shipment

**[FUL-S]**: Carrier-agnostic abstraction
_Substitute_: Internal `ShipmentService` interface; pluggable adapters (GHN, GHTK, Viettel Post, DHL).
_Novelty_: Swap carriers without code change.

**[FUL-C]**: Fulfillment + tracking fused
_Combine_: ShipmentService polls carriers via webhook; tracking updates emit shipment.* events.
_Novelty_: One service, no polling jobs.

**[FUL-A]**: Adapt Amazon "delivery promise"
_Adapt_: Show estimated delivery window at checkout (using carrier SLA + warehouse distance).
_Novelty_: Conversion lift.

**[FUL-M]**: Pick-pack-ship stations
_Modify_: Warehouse UI (Next.js) with barcode scan flow; uses util's QRCodeUtil.
_Novelty_: Mobile-friendly.

**[FUL-P]**: Shipment events as notification trigger
_Put to other uses_: shipment.dispatched → email + SMS via NotificationService.
_Novelty_: Decoupled notification.

**[FUL-E]**: Eliminate returns portal
_Eliminate_: Returns handled via email + manual RMA creation in admin; portal later.
_Novelty_: Saves scope.

**[FUL-R]**: Reverse fulfillment (click-and-collect)
_Reverse_: Pickup at store; reservation → ready-for-pickup event → QR for collection.
_Novelty_: Offline integration.

### Returns (RMA)

**[RET-S]**: Reason taxonomy
_Substitute_: Enum of reasons (defective, wrong-item, no-longer-needed, etc.); per-reason refund policy.
_Novelty_: Analytics-ready.

**[RET-C]**: Returns + refund combined
_Combine_: RMA workflow owns refund issuance.
_Novelty_: No separate refund service.

**[RET-A]**: Adapt Shopify returns
_Adapt_: Restocking fee, return shipping paid by merchant, photo upload as evidence.
_Novelty_: Familiar to merchants.

**[RET-M]**: Partial returns
_Modify_: Return N of M items; prorated refund.
_Novelty_: Reduces support tickets.

**[RET-P]**: RMA as quality signal
_Put to other uses_: RMA reason + photo → quality-dashboard for catalog team.
_Novelty_: Product improvement loop.

**[RET-E]**: Eliminate instant refund
_Eliminate_: All refunds go through approval → processing → capture lifecycle (no instant).
_Novelty_: Reduces fraud.

**[RET-R]**: Reverse returns (exchange-first)
_Reverse_: Default to "exchange for different size/color" before refund.
_Novelty_: Higher retention.

### Customer & Account

**[CUS-S]**: Customer = separate aggregate from User
_Substitute_: User (auth identity) ≠ Customer (commerce profile). Linked by user.customerId.
_Novelty_: B2B future-proof.

**[CUS-C]**: Customer + address book fused
_Combine_: Address entity owned by Customer; orders reference address.id.
_Novelty_: No separate AddressService.

**[CUS-A]**: Adapt Auth0 passwordless
_Adapt_: Email magic link primary; password fallback.
_Novelty_: Lower friction.

**[CUS-M]**: Loyalty points
_Modify_: Points accrue per order; redeem at checkout as discount line item.
_Novelty_: Retention driver.

**[CUS-P]**: Customer events for KYC
_Put to other uses_: High-value customer signals → KYC review queue.
_Novelty_: Compliance.

**[CUS-E]**: Eliminate social login
_Eliminate_: v1 has email-only; add Google/Facebook later.
_Novelty_: Cuts OAuth surface; util already has it though.

**[CUS-R]**: Reverse customer (guest profile)
_Reverse_: Track guest behavior pre-signup via signed cookie; merge on signup.
_Novelty_: Personalization without forcing signup.

### Search & Recommendation

**[SRH-S]**: Elasticsearch per-locale
_Substitute_: Index per locale (`catalog_vi`, `catalog_en`); no multi-language fields.
_Novelty_: Simpler analyzer config.

**[SRH-C]**: Search + autocomplete fused
_Combine_: Single search service handles both query and prefix-suggest.
_Novelty_: One ranking model.

**[SRH-A]**: Adapt Algolia typo-tolerance
_Adapt_: Phonetic + edit-distance fallback; tuned for Vietnamese diacritics (util's VN fonts are evidence of VN focus).
_Novelty_: Vietnamese typo tolerance is hard; explicit tuning needed.

**[SRH-M]**: Faceted search
_Modify_: Category, brand, price range, color, size — Elasticsearch aggregations.
_Novelty_: Standard.

**[SRH-P]**: Search as fraud signal
_Put to other uses_: Search-then-no-purchase → retargeting event.
_Novelty_: Cheap re-engagement.

**[SRH-E]**: Eliminate ML recommendations
_Eliminate_: v1 uses "popular in category" + "frequently bought together"; ML later.
_Novelty_: Cuts scope dramatically.

**[SRH-R]**: Reverse search (question → product)
_Reverse_: "I need a gift for a 5-year-old boy who likes dinosaurs under 500k VND" → LLM-assisted product discovery.
_Novelty_: AI-commerce angle; high novelty, moderate risk.

### Notification

**[NOT-S]**: Event-driven notifications
_Substitute_: NotificationService subscribes to all *.lifecycle events; templates per channel.
_Novelty_: One notification brain; no per-service email code.

**[NOT-C]**: Email + SMS + push combined
_Combine_: Single NotificationService with channel adapter (SendGrid from util, Twilio, FCM).
_Novelty_: Util already has SendGrid wired.

**[NOT-A]**: Adapt SendGrid templates
_Adapt_: Dynamic templates via SendGrid; locale picked from customer profile.
_Novelty_: Marketing-team editable.

**[NOT-M]**: User preferences
_Modify_: Per-channel opt-in/out; quiet hours; digest mode.
_Novelty_: GDPR/PECR compliance.

**[NOT-P]**: Notification events as analytics
_Put to other uses_: email.opened, push.dismissed events.
_Novelty_: Engagement metrics.

**[NOT-E]**: Eliminate SMS notifications
_Eliminate_: Email + push only; SMS later if Vietnam market demands it.
_Novelty_: Cuts cost.

**[NOT-R]**: Reverse notifications (digest-only)
_Reverse_: Daily/weekly digest instead of per-event emails.
_Novelty_: Lower noise.

### Admin & Catalog Management

**[ADM-S]**: Headless admin via Next.js
_Substitute_: No Thymeleaf admin UI; reuse Next.js storefront with role-gated routes.
_Novelty_: Single codebase for staff UI.

**[ADM-C]**: Admin + reporting fused
_Combine_: Admin dashboard shows real-time metrics via the same Next.js routes.
_Novelty_: One frontend, one auth.

**[ADM-A]**: Adapt Strapi/Directus patterns
_Adapt_: Content-as-config for taxonomies; admin edits emit catalog.* events.
_Novelty_: Marketing-friendly.

**[ADM-M]**: Approval workflows
_Modify_: Price changes, promotions require dual approval; saga step for compliance.
_Novelty_: Enterprise-ready.

**[ADM-P]**: Admin actions as audit log
_Put to other uses_: Every admin mutation logs to immutable audit table.
_Novelty_: Compliance-ready.

**[ADM-E]**: Eliminate custom CMS
_Eliminate_: No marketing-content CMS in v1; static Next.js pages only.
_Novelty_: Cuts scope.

**[ADM-R]**: Reverse admin (read-only first)
_Reverse_: Ship read-only admin (catalog view, order view) before write/edit.
_Novelty_: Lower risk; ops gets value first.

### Pricing & Promotion

**[PRC-S]**: Price snapshot per order
_Substitute_: order.priceSnapshot JSON column captures price at order time; catalog price can change freely.
_Novelty_: Critical invariant (per domain-research #2).

**[PRC-C]**: Pricing + promotion combined
_Combine_: Single PricingService owns list price, sale price, promotion discount.
_Novelty_: One calculator.

**[PRC-A]**: Adapt Stripe Coupons
_Adapt_: Codes, % off, fixed amount, BOGO, first-time-buyer; one model.
_Novelty_: Familiar.

**[PRC-M]**: Multi-currency
_Modify_: Display currency conversion at checkout; settlement in base currency.
_Novelty_: Cross-border ready.

**[PRC-P]**: Price events as analytics
_Put to other uses_: pricing.changed feeds competitive-intel job.
_Novelty_: Optional.

**[PRC-E]**: Eliminate tiered pricing
_Eliminate_: No B2B tier pricing in v1; flat price only.
_Novelty_: Cuts scope.

**[PRC-R]**: Reverse pricing (auction/bid)
_Reverse_: Some categories support bid; bid events; saga ends on accept.
_Novelty_: Specialty markets.

### Reviews & Ratings

**[REV-S]**: Verified-purchase only
_Substitute_: Review requires order.id reference; unverified reviews allowed only if order does not exist.
_Novelty_: Anti-spam default.

**[REV-C]**: Reviews + Q&A fused
_Combine_: Same entity, type discriminator (`review` vs `question`).
_Novelty_: One moderation pipeline.

**[REV-A]**: Adapt Amazon verified purchase
_Adapt_: Same; with photo upload.
_Novelty_: Standard.

**[REV-M]**: Helpful-vote ranking
_Modify_: Upvote/downvote; weighted by reviewer tenure.
_Novelty_: Quality signal.

**[REV-P]**: Reviews as fraud signal
_Put to other uses_: Burst-of-5-star pattern → fraud queue.
_Novelty_: Cheap.

**[REV-E]**: Eliminate merchant reply
_Eliminate_: No merchant-reply feature in v1; use admin → flag.
_Novelty_: Cuts scope.

**[REV-R]**: Reverse reviews (sentiment-led)
_Reverse_: Auto-classify tone; show only 3+ star first; 1-2 star behind "see all".
_Novelty_: Polarizing.

### Identity & Auth

**[AUT-S]**: JWT via util's existing stack
_Substitute_: Use util's `CustomSecurityExpressionHandler` + `ICodeJwtGrantedAuthoritiesConvertor`.
_Novelty_: Zero new auth code.

**[AUT-C]**: Auth + customer profile fused
_Combine_: Single BFF endpoint returns session + customer in one call.
_Novelty_: Frontend latency down.

**[AUT-A]**: Adapt Auth0 RBAC
_Adapt_: Roles (`customer`, `staff`, `admin`, `service-account`); permission claims.
_Novelty_: Standard.

**[AUT-M]**: MFA optional
_Modify_: TOTP for staff accounts; not for customer by default.
_Novelty_: Compliance-friendly.

**[AUT-P]**: Auth events as security log
_Put to other uses_: login.success / login.failure → security-event topic.
_Novelty_: SOC-ready.

**[AUT-E]**: Eliminate social login
_Eliminate_: Already counted (CUS-E). Confirm: v1 has email-only.

**[AUT-R]**: Reverse auth (anonymous-first)
_Reverse_: Browse anonymously; auth only at checkout.
_Novelty_: Conversion up.

---

## Phase 2 — Failure Analysis (Pattern Recognition)

Apply anti-bias pivot every 10 ideas. Domains: UX, data-integrity, operational, abuse/threat, integration, dev-experience, legal/compliance. 47 edge cases identified across 12 feature domains.

### UX Failures (8)

**[UX-01]** Checkout progress bar lies — multi-step indicator doesn't match actual step count → users abandon.
**[UX-02]** Cart total updates after promo code applied but before shipping → user sees different price at confirm.
**[UX-03]** Out-of-stock shown only at cart → must be shown earlier (catalog page) and re-validated at checkout.
**[UX-04]** Image-heavy product page on 3G → 5s LCP. Must use Next.js image optimization + AVIF.
**[UX-05]** Search typo in Vietnamese diacritics ("ao" vs "áo") → no results. Phonetic fallback mandatory.
**[UX-06]** Empty cart state shows only "go shopping" — no path to recently-viewed or popular.
**[UX-07]** Address form requires user to pick Province → District → Commune every order → must default to last-used.
**[UX-08]** Currency shown as number only — Vietnamese users expect VND formatting (1.234.567 ₫) — must format with locale.

### Data-Integrity Failures (9)

**[DI-01]** Race: two customers buy last unit. Without per-row lock + reservation TTL → oversell.
**[DI-02]** Saga compensation replay: payment.captured retried → double capture. Idempotency key required.
**[DI-03]** Debezium offset commit lag: outbox event published but offset not committed → duplicate event on restart. Must use exactly-once via transactional outbox + `__transaction_state`.
**[DI-04]** Elasticsearch index drift: catalog updated in Postgres, index out of date until Debezium catches up. Acceptable lag (seconds) must be quantified.
**[DI-05]** Price change after order placed but before payment — order.priceSnapshot must be immutable; late edits ignored.
**[DI-06]** Soft delete leakage: customer marked deleted still appears in admin "active customers" if is_deleted flag forgotten in WHERE clause.
**[DI-07]** Refund references order but order was already partially refunded → cumulative refunds exceed payment. DB CHECK constraint required.
**[DI-08]** Cart merge on login: if two devices have same item → quantity must add, not duplicate row.
**[DI-09]** Soft-delete uniqueness: util's `@SoftUk` annotation works but only if registry validator is wired; if forgotten → unique constraint fails on re-create.

### Operational Failures (8)

**[OP-01]** Kafka broker restart during checkout → consumer rebalance → duplicate payment.captured handling. Idempotent handler required.
**[OP-02]** KRaft single-node setup in dev, multi-node in prod → different RF semantics. Dev must mirror prod RF for outbox table.
**[OP-03]** Elasticsearch index name collisions on re-deploy with stale mapping → must use deterministic index alias per env.
**[OP-04]** Redis OOM during sale → rate limiter starts failing open (or closed?). Need explicit policy.
**[OP-05]** Snowflake worker ID collision if POD_NAME convention breaks (e.g., deployment name doesn't end with `-N`) → SecureRandom fallback masks it silently. Must log + alert.
**[OP-06]** Apicurio Registry down → schema-registered producer fails. Need fallback (use latest compatible schema cached client-side) or fail-fast policy.
**[OP-07]** Grafana dashboard refresh on restart → empty panels. Dashboards must be provisioned from Git (per local-docs/06).
**[OP-08]** Logback log file rotation policy not set → disk fills in days. Must set MaxFileSize + TotalSizeCap.

### Abuse / Threat Failures (7)

**[AT-01]** Card testing attack: 1000s of small failed payments to validate stolen cards. Rate limiter at gateway must block by IP+card-fingerprint.
**[AT-02]** Credential stuffing: bot tries leaked passwords on /login. Account-lockout + CAPTCHA required.
**[AT-03]** CDC event replay → fake order.placed event injected. Must use Avro schema registry with strict evolution; unsigned events rejected.
**[AT-04]** Coupon brute-force: try 10k codes. Coupon lookup must be O(1) and rate-limited.
**[AT-05]** Inventory DoS: add 1000 items to cart with reservation TTL → exhaust stock. Reservation TTL must be short + cap concurrent reservations per user.
**[AT-06]** Webhook forgery: someone POSTs fake Stripe webhooks. Must verify Stripe signature with whitelisted IPs.
**[AT-07]** Admin XSS via product description. Sanitize on render; CSP headers required.

### Integration Failures (7)

**[INT-01]** util/ parent pom `../pom.xml` doesn't exist in this repo → `mvn install` fails. Block for greenfield.
**[INT-02]** Spring Boot 4 GA is 10 Jun 2026; some libraries may not have released Boot-4-compatible versions yet. Pin versions carefully.
**[INT-03]** Stripe API version drift: Stripe rolls out new API versions yearly. Pin to a specific version + test mode toggle.
**[INT-04]** Vietnamese carrier APIs (GHN, GHTK) rate limits + downtime. Must cache + retry with jitter; expose "carrier degraded" UI state.
**[INT-05]** SendGrid deliverability: if domain SPF/DKIM not set → emails go to spam. Ops requirement, not code.
**[INT-06]** Elasticsearch version mismatch between client and server → upgrade must be coordinated.
**[INT-07]** Apicurio Registry content-hash IDs vs Confluent numeric IDs → when consuming from Confluent docs, can't look up directly. Choose one ID scheme early.

### Developer-Experience Failures (4)

**[DX-01]** Local Kafka startup takes 2 minutes → docker-compose with KRaft + healthcheck must be one-command.
**[DX-02]** Debezium connector config drift between dev and prod → connector config in Git + auto-deploy job.
**[DX-03]** No way to seed test data → must have a `seed-dev-data` Maven goal / Spring profile.
**[DX-04]** README assumes prior Kafka/CDC knowledge → onboarding fails for new contributors. Tutorial doc with diagrams required.

### Legal / Compliance Failures (4)

**[LC-01]** Vietnam PDPD (personal data) requires consent + data export on request. Customer profile must support export.
**[LC-02]** PCI-DSS: card data must never touch our servers. Stripe Elements + iframe-only; no PAN logging anywhere.
**[LC-03]** Tax invoice requirements (Vietnam): serialized invoice number, tax authority registration, Jasper-generated PDF with QR code per util's QRCodeUtil.
**[LC-04]** Right-to-be-forgotten: customer deletion must hard-delete PII while keeping anonymized order history.

### Performance / Scalability Failures (3) [Gap-fix §4]

**[PERF-01]** Hot-product thundering herd: 1k concurrent buyers on a flash-sale SKU → DB pool exhaustion + Elasticsearch query timeout cascade. Need circuit breaker + cached stock counter with short TTL fed by CDC.
**[PERF-02]** Cache stampede on category page: cache expires under load → 1000 requests hit Postgres simultaneously. Use single-flight refresh + jittered TTL.
**[PERF-03]** JDBC pool exhaustion under debezium pause: PG JDBC pool sized for normal load, but Debezium's snapshot phase holds long-running transactions → checkout reads time out. Separate pool for CDC; long-running snapshot must use REPEATABLE READ isolation.

### Observability Failures (3) [Gap-fix §4]

**[OBS-01]** Metric cardinality explosion: per-customer-id Prometheus labels → series explosion. White-list allowed label values; reject unknown labels at collector boundary.
**[OBS-02]** Span drop under Kafka consumer retry storms: OTel batch processor can't keep up → silently drop spans. Configure `BatchSpanProcessor` with bounded queue + explicit drop counter metric, never silent.
**[OBS-03]** Loki log cardinality via Stripe customer id embedded in log messages: index becomes unsearchable. Use structured fields (`stripe_customer_id=`) not concatenated strings; log-scrubber at processor.

### Migration / Backwards-Compatibility Failures (3) [Gap-fix §4]

**[MIG-01]** Postgres schema migration while Debezium CDC is running on the affected table: ALTER TABLE on a large table locks CDC reader → outbox events lost. Always expand-then-contract; for big tables use `pg_repack` or similar online migration.
**[MIG-02]** Avro schema breaking change deployed before all consumers upgraded: reader throws on deserialization. Enforce backward + forward compatibility in Apicurio; CI gate that fails when incompatible schema PR is merged.
**[MIG-03]** Elasticsearch index mapping change (reindex required): current search returns partial results during reindex. Use alias-swap pattern (write to `catalog_v2` with new mapping, dual-write, alias swap, drop old).

### Internationalization Beyond Vietnam Failures (3) [Gap-fix §4]

**[I18N-01]** Multi-currency rounding: cart total = `199.005` VND-equivalent, displayed as $0.01 but charged $0.02 (banker's rounding differs). Always round display separately from charge; expose rounding rule in config.
**[I18N-02]** Locale-aware number/date formatting: hard-coded `dd/MM/yyyy` in util's `RootEntity.createdAtFormatted` breaks for en-US locale. Use Java `DateTimeFormatterBuilder` with Locale parameter; format columns per request locale.
**[I18N-03]** RTL languages future-provisioning: Next.js UI mirrors but Postgres schema has hard-coded LTR string comparison (e.g., name sort). Add `BIDI_SUPPORT` config and use ICU collator.

---

## Phase 3 — Five Whys (Idea Development)

Drill to root cause for 13 high-severity edge cases (8 original P0 + 5 follow-ups after deep-review). 5 why-levels each.

### [DI-01] Race condition on last unit oversell

1. *Why* does oversell happen? Two concurrent checkouts both see `on_hand > 0`.
2. *Why* can both see that? Inventory read happens before the write lock is acquired.
3. *Why* isn't there a write lock? `InventoryService.reserve()` reads in a non-transactional context, then writes.
4. *Why* isn't it transactional? The reserve step is split across two services (Inventory and Order).
5. *Why?* Because the saga participants are at service boundaries, not transaction boundaries.

**Root cause:** Saga vs transaction confusion. **Fix sketch:** Single-step atomic reserve inside InventoryService using `SELECT ... FOR UPDATE` within a Postgres transaction, called synchronously from the order-create saga step. Reservation TTL job sweeps stale reservations. CDC publishes `inventory.reserved` event after commit. **Severity:** P0 — directly impacts revenue + customer trust.

### [DI-02] Payment double-capture on saga replay

1. *Why* does double-capture happen? Saga step retries because Kafka redelivers.
2. *Why* does Kafka redeliver? Consumer offset commit didn't happen before handler returned.
3. *Why* didn't commit happen? Handler threw mid-flight (network blip on idempotency check).
4. *Why* did idempotency check itself fail? Stripe `Idempotency-Key` header was missing on retry.
5. *Why?* Saga framework's auto-retry generates a new idempotency key per attempt, not per logical operation.

**Root cause:** Idempotency-key generation is at retry scope, not operation scope. **Fix sketch:** Generate idempotency key from `(order_id, saga_step_name)` once at saga-start; pass through every retry. Stripe webhook handler also stores event.id in `webhook_dedup` table. **Severity:** P0 — financial correctness.

### [DI-03] Debezium outbox duplicate events on restart

1. *Why* duplicate events? Same outbox row read twice.
2. *Why* read twice? Debezium offset not committed for that row before connector crash.
3. *Why* not committed? Default mode is `read_committed` only with Kafka transactions; outbox table is committed but Kafka send may fail after.
4. *Why* not transactional? Using Kafka transactions requires `transactional.id` per producer instance and `__transaction_state` topic with min.isr ≥ broker count.
5. *Why?* Single-broker dev setup hides this; only manifests in prod with 3 brokers.

**Root cause:** Kafka EOS not configured; dev environment doesn't expose the bug. **Fix sketch:** Enable Kafka transactions in prod producer config; run `__transaction_state` with RF=broker-count in dev too; integration test with broker kill mid-send. Alternative: skip Debezium entirely for ref impl → use Spring Modulith outbox (per technical-research shortcut). **Severity:** P0 if Kafka chosen; P1 if Spring Modulith chosen.

### [OP-05] Snowflake worker ID collision silent via SecureRandom fallback

1. *Why* silent? Code path falls back to SecureRandom and logs nothing visible.
2. *Why* fallback exists? For local dev when POD_NAME isn't set.
3. *Why* POD_NAME not set? K8s pod spec doesn't set env var in dev docker-compose.
4. *Why* not set? Dev wasn't thinking about prod worker-id parity.
5. *Why?* Worker ID is invisible until collision causes non-monotonic IDs.

**Root cause:** No contract between deploy manifest and ID generator. **Fix sketch:** `SnowflakeIdGenerator.getWorkerIdFromPod()` must throw when POD_NAME missing in non-dev profile; set `ID_STRICT_MODE=true` for prod; observability metric `snowflake.worker.id.source`. **Severity:** P1 — works in dev, breaks silently in prod under specific conditions.

### [AT-01] Card testing attack bypassing rate limiter

1. *Why* bypass? Rate limit key is IP-only; attacker uses residential proxy pool.
2. *Why* IP-only? Initial design assumed gateway sees true IP via X-Forwarded-For.
3. *Why* trust X-Forwarded-For? Gateway config didn't strip incoming header before adding trusted one.
4. *Why* not strip? Lua script in local-docs/04 only checks Redis bucket key, doesn't validate header chain.
5. *Why?* Rate limiter design assumed trusted ingress; CDN/proxy chain breaks that assumption.

**Root cause:** Trust boundary at gateway undefined. **Fix sketch:** Gateway adds `X-Real-IP` from a trusted-hop list; Lua rate-limit script combines `IP + card-fingerprint hash + ASN`; secondary signal = card BIN velocity check across all users. **Severity:** P0 — direct revenue loss + Stripe account risk.

### [INT-01] util/ parent pom `../pom.xml` missing → `mvn install` fails

1. *Why* fail? Maven can't resolve `<parent>vn.vnpt:be:0.0.1-SNAPSHOT</parent>`.
2. *Why* parent listed? util was originally part of a `vn.vnpt:be` multi-module project.
3. *Why* moved to standalone? Side-project repo doesn't include the parent pom.
4. *Why* not vendored? Original import brought only util/, not the parent.
5. *Why?* Migration copy-paste.

**Root cause:** Maven parent inheritance broken by directory move. **Fix sketch:** Replace `<parent>` with inline `<dependencyManagement>` (since Boot 4 BOM is already imported); OR vendor the parent pom at `pom.xml` root and add `<modules><module>util</module></modules>`. **Severity:** P0 — blocks first build. (Already documented in local-docs/10 §6.9.)

### [UX-05] Vietnamese diacritics typo → zero search results

1. *Why* zero results? Search query "ao" doesn't match "áo" exactly.
2. *Why* exact match? Default Elasticsearch analyzer treats them as distinct tokens.
3. *Why?* Standard analyzer doesn't apply Vietnamese diacritic folding.
4. *Why?* No Vietnamese analyzer plugin installed.
5. *Why?* Reference impl is international by default; Vietnamese was assumed to be a future config.

**Root cause:** Locale-specific analyzer not configured. **Fix sketch:** Install `analysis-vn` plugin (if exists; otherwise custom analyzer with diacritic_folding token filter); OR pre-process queries client-side with both accented + unaccented forms; OR use phonetic analyzer at index time. **Severity:** P1 if target market includes Vietnam; P0 if Vietnam-first.

### [LC-03] Vietnamese tax invoice requirements

1. *Why* specific to Vietnam? Government requires serialized invoice number, VAT registration, QR for verification.
2. *Why* special? Most e-commerce platforms treat invoice as nice-to-have.
3. *Why?* Vietnam tax authority publishes daily invoice register; merchants must reconcile.
4. *Why?* Anti-fraud measure; without it, fake invoices are common.
5. *Why?* Legal mandate: Circular 78/2021/TT-BTC + Decree 123/2020/NĐ-CP.

**Root cause:** Legal jurisdiction-specific feature. **Fix sketch:** Add InvoiceService with serialized number allocator (sequence per tax-authority registration), Jasper template (util has Vietnamese fonts already!), QR code (util's QRCodeUtil), daily batch job to publish invoice register. **Severity:** P0 if selling in Vietnam.

### [DI-04] Elasticsearch index drift on catalog updates

1. *Why* does index drift? Debezium publishes new state, but ES bulk-write is async.
2. *Why* async? ES bulk processor buffers writes for throughput.
3. *Why* buffered? Single-event indexing was 5x slower under load test.
4. *Why* throughput-critical? CatalogService reads from ES, not PG, for search.
5. *Why?* Search must be fast; PG `LIKE` queries don't support Vietnamese analyzer.

**Root cause:** Split-brain between source-of-truth (PG) and read model (ES), with asynchronous best-effort propagation. **Fix sketch:** Quantify acceptable lag (target: 95% of events published within 5s), expose `catalog.index.drift.lag.seconds` gauge, alert if >30s; idempotent reindex job catches up any drift; consumers must tolerate "stale by N seconds" semantics. **Severity:** P1 (acceptable drift) but flip to P0 if business promises consistency.

### [DI-07] Cumulative refunds exceed original payment

1. *Why* would cumulative refunds exceed payment? Sum of refund amounts not checked against authorization.
2. *Why* not checked? Refund service trusts caller.
3. *Why* trust caller? Refund flow initiated by admin/CS tool.
4. *Why* no DB enforcement? Application code path runs without row lock.
5. *Why?* Refund record is a separate row, not enforced by aggregate root.

**Root cause:** Missing aggregate-level invariant. **Fix sketch:** Add `order.aggregate.refund_validator`: every refund-issued event handler runs `SELECT SUM(amount_cents) FROM refund WHERE order_id = ? FOR UPDATE`, aborts if `> order.amount_cents`. Add DB-level partial unique constraint or check constraint using PG's CHECK. Refund is idempotent on `(order_id, refund_idempotency_key)`. **Severity:** P0 — financial correctness.

### [AT-03] CDC event injection → fake order.placed

1. *Why* can attacker inject events? Kafka topic was assumed internal.
2. *Why* assumed internal? Kafka cluster is on private VPC.
3. *Why* private VPC alone insufficient? Compromised pod can still produce to Kafka with credentials.
4. *Why* does compromised pod have producer credentials? Service-to-service auth uses shared service-account in dev/staging.
5. *Why?* Convention from monolith era; no per-service identity boundary in event layer.

**Root cause:** Missing event-level authentication. **Fix sketch:** mTLS between Kafka and services + each producer signed (HMAC) event-header with per-service key; Apicurio enforced strict schema evolution rejects malformed payloads; consumer re-verifies signature. Defense-in-depth: even if attacker injects, Avro schema mismatches cause consumer-side rejection. **Severity:** P0 — order fraud path.

### [OP-01] Kafka broker restart mid-checkout → duplicate handling

1. *Why* duplicate? Consumer rebalance reassigns partitions mid-handler.
2. *Why* duplicate handling? Handler returned before offset commit.
3. *Why* before commit? Network blip between handler return and commit ACK.
4. *Why* blip so common? Kafka commit is a synchronous round-trip.
5. *Why?* Default `enable.auto.commit=true` masks the issue in dev where it's almost instant.

**Root cause:** Async boundary between handler-completion and offset-durability. **Fix sketch:** `enable.auto.commit=false`; manual commit after handler success OR use read-process-commit pattern with idempotent handler backed by `processed_event` table keyed by event.id. Same dedup table as Stripe webhooks — single mechanism across all event sources. **Severity:** P0 if event loss/duplication leads to revenue drift.

### [LC-01] Vietnam PDPD data export on request

1. *Why* export required? Customer invokes data-subject-access right.
2. *Why* non-trivial? Data is spread across 13 services / 13 PG databases.
3. *Why* spread? Microservice boundary per domain.
4. *Why* hard to aggregate? No central customer data catalog.
5. *Why?* Customer is an aggregate ID, but the data lives in unrelated tables across services.

**Root cause:** Data-per-service trades sovereignty for join-ability. **Fix sketch:** Maintain a `customer_data_registry` — declarative table listing each (service, table, columns, format) per data category. Export job runs on-demand, joins via Customer aggregate ID, emits a single JSON/ZIP bundle. Hard-deletes on forget: triggers per-table tombstone via CDC. **Severity:** P0 if Vietnamese customers; P1 otherwise.

---

## Phase 4 — Six Thinking Hats (Action Planning)

Six-hat scan to convert Phase 2+3 outputs into P0/P1/P2 ranking and risk register.

### ⚫ Black Hat — Risk Register

| ID | Risk | Severity | Likelihood | Owner | Mitigation |
|---|---|---|---|---|---|
| **R-01** | util/ parent pom missing — `mvn install` blocked | Critical | Certain | Build Eng | Vendor parent pom OR inline dependencyManagement (see INT-01) |
| **R-02** | Inventory oversell race condition | Critical | High | InventoryService | SELECT FOR UPDATE + reservation TTL + saga atomic reserve (DI-01) |
| **R-03** | Payment double-capture on saga replay | Critical | Medium | PaymentService | Stable idempotency key per (order, step); webhook dedup table (DI-02) |
| **R-04** | Debezium outbox duplicate events | Critical | High | Platform | Kafka transactions + RF=broker-count in dev too; or Spring Modulith (DI-03); default = Modulith outbox unless microservice split decided later |
| **R-05** | Card-testing attack via residential proxies | Critical | High | Gateway + Fraud | Trust-boundary fix at gateway; card-fingerprint hash + BIN velocity (AT-01) |
| **R-06** | Vietnamese tax-invoice compliance | Critical | Certain (VN market) | InvoiceService | Serialized number allocator + Jasper template + QR + daily batch (LC-03) |
| **R-07** | Vietnamese diacritic search zero-results | High | Certain (VN market) | SearchService | analysis-vn plugin + diacritic folding (UX-05) |
| **R-08** | Snowflake worker-id collision silent | Medium | Medium | Platform | Throw if POD_NAME missing in prod; metric + alert (OP-05) |
| **R-09** | Spring Boot 4 ecosystem immaturity | High | High | Build Eng | Pin versions; integration test before upgrade; technical-research.md has matrix; Spring Cloud 2025.1 libraries lag 4–8 weeks |
| **R-10** | Soft-delete uniqueness regression | Medium | Low | Base infra | Enforce @SoftUk via @EntityListeners; lint check |
| **R-11** | Redis OOM during sale | Medium | Medium | Platform | Circuit breaker; explicit fail-open policy; rate-limit metrics |
| **R-12** | Stripe API version drift | Low | Medium | PaymentService | Pin API version; integration test on upgrade |
| **R-13** | GHN/GHTK carrier downtime | Low | High | FulfillmentService | Retry + jitter + carrier-degraded UI |
| **R-14** | Apicurio Registry single point of failure | Medium | Low | Platform | Cache schemas client-side; failover policy |
| **R-15** | PCI scope creep | Critical | High | Security | Stripe Elements iframe only; lint to forbid PAN in logs; OpenTelemetry log redaction for any field matching `\d{13,19}`; deny-list the default request-body logger |

### 🟡 Yellow Hat — Benefits (why this matters)

- **First Java/Spring Boot 4 reference ecommerce** — niche gap confirmed by market research; high visibility target for senior Java devs + architects.
- **util/ gives 60% of boring infrastructure for free** — audit, Snowflake, Jasper (with VN fonts!), SendGrid, Telegram errors, Excel, OAuth2 helpers.
- **Domain research already validated** the 13-service boundaries and 8 invariants — no need to re-derive architecture.
- **Vietnam-first** is a defensible niche — VN ecommerce is large, growing, and under-served by Java references.
- **Event-driven + CDC** is the 2025+ default — choosing Kafka KRaft + Debezium is forward-compatible.

### 🟢 Green Hat — Alternatives to consider

- **Spring Modulith instead of full microservice** for first release — gets CDC behavior without Debezium complexity; can split later.
- **Outbox via Spring Modulith > Debezium** for reference impl — fewer moving parts (per technical-research shortcut).
- **Skip Apicurio/Avro; use JSON Schema** for first 3 months — fewer dependencies, faster onboarding.
- **Single Stripe integration only** — defer Adyen/Braintree until requested.
- **Hand-roll saga** — Spring Statemachine adds complexity for a reference; could use simple state-field-on-aggregate instead.

### ⚪ White Hat — Data gaps to fill before architecture

- Boot 4 + Spring Cloud 2025.1 exact patch versions (technical-research has matrix but needs verification in integration test).
- Stripe API version to pin (currently 2025-XX).
- Vietnamese tax authority registration flow specifics (accountant input).
- Carrier SLA targets (GHN, GHTK, Viettel Post).
- Estimated daily order volume for capacity planning.

### 🔴 Red Hat — Gut feel

- P0 list is heavy on payment/inventory/auth — that's correct, those are the trust-bearing systems.
- util/'s `BaseEntity` + `Snowflake` + `Jasper` will be the most-reused pieces — invest there first.
- Debezium vs Spring Modulith is the single biggest architecture decision; current vibe is "Modulith first, split later."
- Diacritic search feels like it should be P0 for any VN-target launch — easy to underestimate until launch day.

### 🔵 Blue Hat — Process meta

- Sprint 0 = foundations (util/ parent pom fix, integration-test scaffolding, dev docker-compose).
- Sprint 1 = catalog + inventory (DI-01 root-cause fix here).
- Sprint 2 = cart + checkout (single-page, sagaflow).
- Sprint 3 = payment + idempotency (DI-02).
- Sprint 4 = order + fulfillment.
- Sprint 5 = customer + auth.
- Sprint 6 = search + recommendation.
- Sprint 7 = returns + RMA.
- Sprint 8 = admin + reporting.
- Sprint 9 = notification + tax invoice (LC-03).
- Sprint 10 = observability + chaos testing (validate R-02..R-15 mitigations).

**Capacity assumption (added by deep-review):** 1 sprint = 2 weeks, 1 full-stack developer with infrastructure help on-demand. Under that, the 10-sprint plan maps to ~20 weeks = ~5 months solo. If team is 3 devs, parallelize Sprints 1+6 (catalog + search share infra), 2+5 (cart/checkout + customer/auth share auth context), 4+8 (order/fulfillment + admin/reporting share read-model), 7+9 (returns + tax invoice share customer-data layer). With 3 devs the plan compresses to ~4 months for trust-bearing core + 2 months hardening = ~6 months to launch candidate. With 1 dev, accept P1 features (search, recommendation, returns, admin) slip into post-launch.

### P0/P1/P2 Final Ranking — Full Ecommerce Product Feature Set

#### **P0 — Must-have (Trust-bearing core)**

| Feature | Domain | Why P0 |
|---|---|---|
| Catalog (product + variant + price snapshot) | CatalogService | Foundation; everything depends on it |
| Inventory (per-warehouse ledger, FOR UPDATE reserve) | InventoryService | Trust-bearing; R-02 |
| Cart (anonymous, versioned, multi-seller-ready) | CartService | Foundation for checkout |
| Checkout (single-page, Stripe PaymentIntent lifecycle owned) | CheckoutService | Trust-bearing; revenue |
| Payment (Stripe-only, idempotency-keyed, webhook dedup) | PaymentService | R-03, R-15 |
| Order (event-sourced, price snapshot immutable) | OrderService | Trust-bearing |
| Saga orchestrator — Spring Modulith outbox (default Green-Hat path) | Platform | R-04 mitigation; simpler than Debezium — picks this row |
| Saga orchestrator — Spring Statemachine + Debezium (alternate) | Platform | R-04 carries Critical residual risk; ADR defers to architecture phase |
| Customer + Auth (email+password, JWT via util, RBAC) | CustomerService + AuthService | Trust-bearing |
| Address book (Province/District/Commune from util) | CustomerService | Required for checkout |
| Multi-warehouse inventory with reservation TTL | InventoryService | R-02 mitigation |
| Stripe webhook handler (idempotent) | PaymentService | R-03 mitigation |
| Dev experience: docker-compose with KRaft + Debezium + ES + Redis + Postgres + Apicurio | Platform | DX-01, DX-02 |
| Vietnamese tax invoice (serialized + QR + Jasper) | InvoiceService | R-06 / LC-03 |
| Vietnamese address autocomplete | CustomerService | UX-07 |
| Vietnamese diacritic search | SearchService | R-07 / UX-05 |
| Rate limiter (gateway IP+fingerprint, BIN velocity) | Gateway | R-05 / AT-01 |
| Audit trail (immutable admin-mutation log) | Platform | LC-01, compliance |

#### **P1 — Should-have (Launch quality)**

| Feature | Domain | Why P1 |
|---|---|---|
| Fulfillment (carrier-agnostic adapter: GHN/GHTK/Viettel Post) | FulfillmentService | Revenue path |
| Order timeline (placed → delivered) | OrderService | UX-01, support reduction |
| Order edit-after-pay (TTL-bounded) | OrderService | UX-02 |
| Returns (RMA, exchange-first, partial returns) | ReturnsService | UX-08, retention |
| Reviews (verified-purchase, photo upload, helpful-vote) | ReviewsService | Conversion |
| Notification (event-driven, email+push via util's SendGrid) | NotificationService | Customer comms |
| Admin (Next.js, read-first then write) | AdminService | Operational |
| Search faceting (Elasticsearch aggregations) | SearchService | UX-03 |
| Soft-delete uniqueness via util's @SoftUk | Base infra | DI-09 |
| Snowflake strict-mode + observability | Platform | R-08 |
| Stripe Coupons | PricingService | Conversion |
| Loyalty points | CustomerService | Retention |
| Single-page checkout (one route, one POST) | CheckoutService | UX-01 |
| Order.placed → Elasticsearch revenue dashboard | Analytics | Real-time |
| Apicurio schema registry (prod-grade) | Platform | R-14 |
| OpenTelemetry instrumentation across services | Platform | Observability |
| Grafana + Prometheus + Loki + Tempo | Platform | Observability |

#### **P2 — Nice-to-have (Post-launch polish)**

| Feature | Domain | Why P2 |
|---|---|---|
| ML recommendations (popular + frequently-bought) | SearchService | SRH-E deferral |
| Multi-currency | PricingService | Cross-border (only triggered if expanding beyond VN — see Scope Boundaries) |
| Tiered B2B pricing | PricingService | Enterprise — explicit non-goal for v1, kept here as exclusion marker so PRD doesn't quietly add it |
| Social login (Google/Facebook) | AuthService | CUS-E deferral |
| SMS notifications | NotificationService | NOT-E deferral |
| B2B customer profile + tiered catalog | CustomerService | Future |
| Pre-order / made-to-order | OrderService | INV-R deferral |
| Click-and-collect | FulfillmentService | FUL-R deferral |
| ML product discovery (LLM-assisted) | SearchService | SRH-R deferral |
| Custom CMS for marketing content | AdminService | ADM-E deferral |
| Returns portal (self-service) | ReturnsService | FUL-E deferral |
| Inventory batch/lot/serial tracking | InventoryService | INV-E deferral |
| MFA for customer accounts | AuthService | AUT-M deferral |
| Daily/weekly email digest | NotificationService | NOT-R deferral |
| Bulk catalog import/export (util's Excel framework) | AdminService | P2 nice |
| Image-search via CLIP embeddings | SearchService | CAT-R deferral |
| A/B testing framework | Platform | Future |
| Auction/bid mode for specialty categories | PricingService | PRC-R deferral |

---

## Idea Organization Summary

**Total ideas generated: 189** (90 SCAMPER + 59 EC + 13 deep-drilled whys + 15 risks + 56 P0/P1/P2 features, accounting for the 13 drills being a *subset* of the 59 ECs; 2 P2 features are explicit non-goal markers (Multi-currency, Tiered B2B pricing), included in count for completeness; plus 11 cross-cutting patterns in the Organization section)
- SCAMPER: 90 feature items (15 domains × 7 lenses, no dupes)
- Failure Analysis: 59 edge cases across 11 failure domains (added Performance / Observability / Migration / i18n)
- Five Whys: 13 root-cause deep dives (8 original + 5 high-severity follow-ups: DI-04, DI-07, AT-03, OP-01, LC-01)
- Six Thinking Hats: 15 risk-register entries + 19 P0 + 18 P1 + 19 P2 ranked features (P0 = 18 originally + 1 from saga-split; P2 has 2 explicit non-goal markers, NOT dedup)

**Cross-cutting patterns:**
- Vietnamese-first optimization (diacritics, tax invoice, address autocomplete, VN fonts via util's Jasper) — unique positioning
- Idempotency is the single most-mentioned concept (payment, webhook, saga, Debezium, dedup) — design for it first
- util/ provides 60% of "boring infrastructure" — audit + Snowflake + Jasper + SendGrid + Telegram + Excel + OAuth2 are all there
- Trust-bearing systems (payment, inventory, auth, tax) are P0 — everything else can be cut without breaking commerce

**Breakthrough concepts:**
- Snowflake worker-id observability metric (turns invisible bug into monitored condition)
- Spring Modulith outbox alternative to Debezium for reference impl (simpler, same semantics)
- Saga idempotency-key = hash(order_id, step_name), not per-retry
- Vietnamese tax-invoice Jasper template already half-built via util's Jasper + VN fonts + QRCodeUtil
- Hand-rolled saga beats Spring Statemachine for reference readability

**Implementation-ready ideas (no further work needed):**
- util/ parent pom fix (one pom edit)
- P0 feature list with owner assignment
- 15-entry risk register with mitigations

## Scope Boundaries — What We Did NOT Explore

The following were out of scope for this brainstorming session and must be addressed before / during PRD phase:

- **Business model** — B2C vs B2B vs marketplace. Affects schema (sellerId on Product? catalog tenancy?). Default assumed: B2C.
- **Geographic scope beyond Vietnam** — VN-first was assumed. ASEAN expansion changes tax engine, currencies, address hierarchies.
- **Merchant-side pricing strategy** — list price, sale price, promotional pricing, contract pricing all assumed = single PricingService. Multi-currency and Tiered B2B pricing are explicit non-goals for v1 (see P2 table for the exclusion marker).
- **Tax jurisdictions beyond Vietnam** — single Vietnamese tax engine; expanding to other ASEAN requires InvoiceService generalization.
- **Currency volatility handling** — multi-currency display assumes static FX rate; real FX feed is out of scope.
- **Customer support tooling** — assumed that admin + email notifications cover support needs. Helpdesk integration, agent tooling, refund workflow UI are not designed.
- **Marketing automation** — abandoned-cart triggers, recommendation emails, segmentation, A/B testing all deferred to P2 features but not analyzed as a marketing capability.
- **Mobile native apps** — iOS / Android first-class experience not designed; only Next.js web + responsive.
- **B2B-specific features** — purchase orders, account-level pricing, credit limits, quote-to-order — all P2 but not designed.
- **Omnichannel / POS** — in-store / pickup / pop-up locations not in P0–P2 list.
- **Marketplace governance** — seller onboarding, dispute resolution, payout ledger — relevant if marketplace model chosen later.
- **Sustainability / ESG reporting** — carbon accounting per shipment, ethical-sourcing labels — not addressed.

PRD writer should treat this list as "open questions to confirm scope" rather than missing requirements.

## Session Summary and Insights

**Key Achievements:**
- 189 ideas generated and organized across 4 techniques (de-duped across phases; raw 233 before de-dup)
- Full ecommerce product feature set ranked P0/P1/P2 (56 features total: 19 P0, 18 P1, 19 P2)
- Risk register with 15 entries, mitigations, owners
- 13 root-cause analyses on highest-severity edge cases (8 initial + 5 follow-ups)
- 59 edge cases across 11 failure domains (added Performance / Observability / Migration / i18n in deep-review pass)
- Cross-cutting patterns identified: idempotency, Vietnamese-first, util reuse, defense-in-depth for payment/auth

**Session Reflections:**
- Vietnamese-first focus turned what could have been a generic ecommerce brainstorm into a niche-defining exercise
- util/ base library is a massive force multiplier — design decisions there cascade
- Risk register shows Critical concentration in payment + inventory + auth + tax — exactly the trust-bearing quartet
- User asked for "complete product, not MVP" — output reflects full scope (56 features: 19 P0, 18 P1, 19 P2) instead of MVP cut
- Deep-review pass surfaced 5 gaps (measurement count, saga decision-split, missing EC domains, missing 5 drills, severity mismatches) — all addressed before PRD handoff
- Capacity assumption made explicit (1 dev = 5 months solo, 3 devs = 6 months to launch candidate)

## Next Steps (handoff to PRD phase)

1. **Now**: User reviews session doc (`_bmad-output/brainstorming/brainstorming-session-2026-07-06-1119.md`)
2. **Next BMad step**: `bmad-prd` — produces PRD using P0/P1/P2 ranking, 8 invariants from domain research, and util reuse plan
3. **After PRD**: `bmad-create-architecture` — make the Spring Modulith vs full microservice call (Green Hat alternative)
4. **Then**: `bmad-create-epics-and-stories` — break P0 into buildable epics
5. **Then**: `bmad-check-implementation-readiness` — gate before code
6. **Then**: `bmad-sprint-planning` — produce sprint status
7. **Then**: `bmad-story-automator` — automated build across all stories