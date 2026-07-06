
```markdown
// 05-saga-and-checkout-flow.md
# 5. Saga & Checkout Flow

Checkout is a complex multi-step process requiring transactional integrity across multiple microservices. We use an Orchestration-based Saga pattern.

## Orchestration vs. Choreography Trade-offs
- **Choreography** shines for *post-commit side effects* (e.g., Notification reacting to `OrderPlaced`). It is the wrong tool for checkout because there's no central state to debug, timeouts/parallel branches are painful, and compensation ordering is implicit.
- **Orchestration** is correct for checkout: explicit state machine, parallel branches, centralized timeout/retry/compensation. We use Spring Statemachine. State is persisted in Order Orchestrator's Postgres with optimistic locking.

## Checkout Saga Flow

1. **Initiation:** Customer POSTs checkout request (cart, address, payment method) to Order Orchestrator.
2. **Parallel Branches (Java 25 Structured Concurrency):**
   - Orchestrator fans out commands: `ComputeTax` (Tax Service), `ApplyPromotion` (Promotion Service).
   - Uses Java 25 `StructuredTaskScope` to wait for both replies, propagating `Scoped Values` for trace context.
3. **Sequential Reservation Branch:**
   - Validates stock and reserves it via `ReserveStock` command to Inventory Service. (Inventory DB uses optimistic locking to prevent oversell).
4. **Payment:**
   - Initiates payment via `CapturePayment` command to Payment Service. (Payment service enforces idempotency keys).
5. **Completion:**
   - On payment success, triggers Delivery Service (`CreateShipment`).
   - Orchestrator commits local state (Order = PLACED) and publishes `OrderPlaced` event to `order.lifecycle` topic.
6. **Choreographed Reactions:**
   - Recommendation Service consumes `OrderPlaced` to update user behavior models.
   - Notification Service consumes `OrderPlaced` and sends email confirmation.
   - Search Service consumes `OrderPlaced` to update purchase popularity metrics.

## Compensation Flows
If any step fails, the Orchestrator state machine transitions to a compensating state:
- Payment Fails → `ReleaseStock` command to Inventory.
- Delivery Fails (post-payment) → `RefundPayment` command to Payment, then `ReleaseStock`.
- All commands and replies are idempotent. DLQ handles poison pills after N retries with exponential backoff + jitter.