Design a production-grade microservice ecommerce platform using Java 25 and Spring Boot 4. The system must showcase event-driven architecture, CDC with Debezium & Kafka, Elasticsearch search, Prometheus observability, and a custom Token Bucket rate limiter with both RBAC and IP-based policies.

## 1. Overall Architecture
- Multiple independent microservices, each with its own PostgreSQL database (database-per-service).
- Asynchronous communication via Apache Kafka.
- API Gateway (Spring Cloud Gateway) as single entry point, handling cross-cutting concerns: authentication, rate limiting, circuit breaking, and routing.

### Core Services
| Service | Responsibility |
|---------|----------------|
| Customer Service | User accounts, JWT authentication, roles (USER, ADMIN, SELLER), addresses |
| Product Service | Product catalog, inventory, categories – source of truth for product data |
| Search Service | Consumes product CDC events, indexes into Elasticsearch, full-text search API |
| Rating Service | Product ratings & reviews, aggregates average rating per product |
| Recommendation Service | User behavior events, personalized recommendations |
| Promotion Service | Discounts, coupons, campaigns |
| Tax Service | Tax calculation based on location and product category |
| Delivery Service | Shipment options, tracking, carrier integration |
| Location Service | Geocoding, distance estimation |
| Payment Service | Payment processing (mock Stripe/PayPal), idempotency |
| Order Orchestrator | Saga-based checkout flow coordination |
| Notification Service | Email/SMS/push after events |

## 2. Tech Stack & Versions
- **Backend:** Java 25, Spring Boot 4, Spring Cloud Gateway, Spring Security, Spring Data JPA, Spring Kafka
- **Frontend:** Next.js 15 (secondary focus)
- **Messaging:** Apache Kafka
- **CDC:** Debezium connectors (PostgreSQL → Kafka)
- **Search:** Elasticsearch 8.x
- **Rate Limiter State:** Redis (centralized token buckets)
- **Observability:** Prometheus, Grafana, Micrometer
- **Containerization:** Docker Compose (local), Kubernetes (production)

## 3. Rate Limiting Design (Token Bucket with RBAC & IP-Based)

### 3.1 Algorithm
Each bucket has `capacity`, `refillRate` (tokens per second), `tokens`, and `lastRefillTimestamp`. On each request, tokens are refilled according to the elapsed time since last refill. If `tokens >= 1`, one token is consumed and the request is allowed. Otherwise, the request is rejected with HTTP 429 Too Many Requests.

### 3.2 Combined Policies
- **RBAC‑based:** Buckets are keyed by user role extracted from the JWT. Different roles get different limits (e.g., ANONYMOUS, USER, ADMIN, SELLER). Each role has a defined capacity and refill rate.
- **IP‑based:** Separate buckets per client IP (from `X-Forwarded-For` or direct connection). A global IP limit applies across all endpoints, preventing abuse from a single IP regardless of authentication.
- **Per‑endpoint overrides:** Specific paths and HTTP methods can override default role/IP limits (e.g., a login endpoint might have stricter limits than a public product listing).
- **Strict evaluation:** A request is allowed only if both the role bucket (if authenticated) and the IP bucket have tokens. The stricter of the two limits takes effect. Unauthenticated users are only subject to the ANONYMOUS role limit and IP limit.

### 3.3 Implementation Details
- Implement as a custom `GatewayFilter` in Spring Cloud Gateway.
- For each request, extract the route, user role, and client IP. Look up matching rate limit rules.
- Execute an atomic Lua script in Redis for each applicable bucket key. The script calculates current tokens after refill, deducts a token if available, and returns the allowed/denied decision along with remaining tokens.
- On denial, the filter immediately returns a 429 response with `Retry-After` header. On success, remaining token counts are added as response headers (`X-RateLimit-Remaining`, `X-RateLimit-Limit`).
- The token bucket state is stored in Redis with a TTL to clean up inactive buckets automatically.

### 3.4 Distributed Considerations
- Because multiple API Gateway instances may handle requests, Redis acts as the shared state store. The Lua script guarantees atomicity, eliminating race conditions.
- Optionally, the same rate limiter can be packaged as a Spring Boot starter and reused internally by microservices to protect against misbehaving service-to-service calls (using service identity instead of user roles).

## 4. CDC & Data Duplication
- Debezium captures inserts/updates/deletes from Product Service’s `products` table → Kafka topic `cdc.products.product`.
- Search Service consumes this topic and updates Elasticsearch (upsert by product ID).
- Rating Service publishes `ProductRated` events → Search Service updates average rating in Elasticsearch.
- Promotion events → Product Service caches active promotions locally (eventual consistency) to display sale prices without calling Promotion Service on every page load.
- Customer address changes → Tax & Delivery Services cache the latest address, reducing cross-service calls during checkout.

## 5. Elasticsearch Product Search
- Endpoint: `GET /api/search?q=...&category=...&minPrice=...&maxPrice=...&sort=rating&page=0&size=20`
- Index mapping supports: full-text search (name, description), filtering (category, price range, rating), sorting (price, rating, newest), and autocomplete (edge-ngram or completion suggester).
- Data is kept in sync via Kafka; reindexing is done by replaying from a compacted topic.

## 6. Observability with Prometheus
- All services expose `/actuator/prometheus` via Micrometer.
- Custom metrics for the rate limiter: counter for allowed/denied requests by bucket type (IP, role), and a gauge for remaining tokens per bucket.
- Grafana dashboards display: per-endpoint request rates and latencies (p95/p99), rate limit rejection breakdown by role/IP, Kafka consumer lag, CDC pipeline lag, and business KPIs (orders per minute, search queries per second).

## 7. Checkout Saga (Example)
1. Customer POSTs checkout request (cart, address, payment method).
2. Order Orchestrator:
    - Validates stock via Product Service (synchronous with timeout).
    - Reserves stock (publishes `StockReserved` event after deducting inventory).
    - Calculates tax via Tax Service (async request/reply or gRPC, using cached address).
    - Applies promotions.
    - Initiates payment via Payment Service.
    - On payment success, triggers Delivery Service to create shipment.
    - Publishes `OrderPlaced` event.
3. If any step fails, compensating events (`ReleaseStock`, `RefundPayment`) are published.
4. Other services react to `OrderPlaced`: Recommendation updates, Notification sends confirmation.

Saga coordination can be choreography (pure events) or orchestration (Order Orchestrator commands). Discuss trade‑offs.

## 8. Deliverables
Please provide:
1. **Textual architecture diagram** describing all services, Kafka topics, Redis, Elasticsearch.
2. **Kafka topic inventory** – each topic, its producer(s), consumer(s), and message schema overview.
3. **Rate limiter design** – detailed explanation of how RBAC and IP policies are evaluated and combined, how the Redis atomic script works, and how configuration is structured (without necessarily including a full YAML snippet).
4. **Implementation roadmap** – suggested order:
    - Service Registry, Config Server, API Gateway
    - Customer, Product services (CRUD)
    - CDC pipeline + Search Service
    - Rate limiter on Gateway
    - Remaining services incrementally
    - Observability stack
5. **Docker Compose setup** – all infrastructure (Kafka, PostgreSQL, Redis, Elasticsearch, Debezium) plus the services.
6. **Optional: Folder structure** for a multi-module Maven/Gradle project.

## 9. Target Audience
Mid-level software engineer aiming to demonstrate mastery of modern microservices, event-driven architecture, and custom infrastructure components. The design should be realistic, production‑aware, and explain every architectural decision.