// 03-kafka-and-cdc-strategy.md
# 3. Kafka & CDC Strategy

Data replication and business events are handled differently. Data replication uses direct table CDC, while business events use the Outbox pattern.

## Topic Inventory

| Topic | Partitions | Cleanup | Producers | Consumers | Schema (Avro) |
|---|---|---|---|---|---|
| `cdc.products.product` | 6 | compact | Debezium (Product DB) | Search, Recommendation | `ProductCDC{op,id,name,desc,price,categoryIds,media,updatedAt}` |
| `cdc.products.category` | 3 | compact | Debezium | Search | `CategoryCDC` |
| `cdc.inventory.stock-item` | 8 | compact | Debezium (Inventory DB) | Search, Order Orchestrator | `StockItemCDC{productId,available,reserved,version}` |
| `cdc.customers.address` | 4 | compact | Debezium (Customer DB) | Tax, Delivery | `AddressCDC{userId,addressLine,city,country,geo}` |
| `order.lifecycle` | 12 | delete, 90d | Order Orchestrator | Notification, Recommendation, Audit, Search | `OrderEvent` (union: `OrderPlaced`, `OrderCancelled`, `OrderShipped`, `OrderDelivered`) |
| `order.saga.commands` | 12 | delete, 7d | Order Orchestrator | Inventory, Payment, Tax, Promotion, Delivery | `SagaCommand` (union: `ReserveStock`, `CapturePayment`, `ComputeTax`, `ApplyPromotion`, `CreateShipment`, compensations) |
| `order.saga.replies` | 12 | delete, 7d | Inventory, Payment, Tax, Promotion, Delivery | Order Orchestrator | `SagaReply` (union: `StockReserved`, `StockRejected`, `PaymentCaptured`, `PaymentFailed`, …) |
| `payment.events` | 8 | delete, 90d | Payment Service | Order Orchestrator, Notification, Audit | `PaymentEvent` (union: `PaymentSucceeded`, `PaymentFailed`, `RefundCompleted`) |
| `rating.events` | 6 | delete, 365d | Rating Service | Search, Product (avg cache), Notification | `ProductRated`, `ReviewAdded`, `ReviewModerated` |
| `recommendation.events` | 8 | delete, 30d | BFF (clickstream), Order, Rating | Recommendation Service | `UserBehaviorEvent` (union: `Viewed`, `AddedToCart`, `Purchased`, `Rated`) |
| `promotion.events` | 4 | compact | Promotion Service | Product, Order Orchestrator, Search | `PromotionActivated`, `PromotionExpired`, `PromotionUpdated` |
| `cart.events` | 6 | delete, 30d | Cart Service | Recommendation, Audit | `CartItemAdded`, `CartAbandoned`, `CartMerged` |
| `notification.events` | 8 | delete, 30d | Many | Notification Service | `NotificationRequested` (union: `Email`, `SMS`, `Push`) |
| `delivery.events` | 6 | delete, 180d | Delivery Service | Order Orchestrator, Notification | `ShipmentCreated`, `ShipmentInTransit`, `ShipmentDelivered` |
| `audit.events` | 6 | delete, 7y | All services | Audit Service → S3 Object Lock | `AuditEvent{actor,action,resource,before,after,traceId,ts}` |
| `dlq.<topic>` | =source | delete, 30d | Consumer DLQ producer | Dead-letter sink → S3 + alert | `DeadLetter{originalTopic,originalValue,headers,error,ts}` |

## Conventions
- **Partition Keys:** Indicated in schema. All topics carry W3C `traceparent` header for distributed tracing propagation.
- **Compaction:** Used for state-replication topics (CDC) to maintain latest known state indefinitely.
- **Retention:** Business events use time-based retention mapped to business requirements (e.g., 7 years for audit compliance).

## Elasticsearch Search Integration
- **Search Service** consumes `cdc.products.product` and upserts by product ID into Elasticsearch 8.x.
- Rating Service publishes `ProductRated` events → Search Service updates average rating fields in ES.
- Reindexing is done by replaying the compacted `cdc.products.product` topic.
- Endpoint supports: `GET /api/search?q=...&category=...&minPrice=...&maxPrice=...&sort=rating&page=0&size=20`