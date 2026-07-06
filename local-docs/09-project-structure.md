
```markdown
// 09-project-structure.md
# 9. Project Structure

A multi-module Maven project keeps shared platform code separate from business domains while enforcing consistent tooling.

```text
ecommerce-platform/
├── pom.xml                              # parent: dependencyManagement, pluginManagement
├── settings.xml                         # (optional) internal repo mirrors
├── LICENSE
├── README.md
├── Makefile                             # convenience: make up / make down / make topics / make seed
│
├── docs/
│   ├── adr/
│   │   ├── 0001-database-per-service.md
│   │   ├── 0002-outbox-pattern.md
│   │   ├── 0003-rate-limiter-design.md
│   │   ├── 0004-saga-orchestration-vs-choreography.md
│   │   ├── 0005-schema-registry-and-compatibility.md
│   │   └── 0006-virtual-threads-adoption.md
│   ├── diagrams/
│   ├── runbooks/
│   │   ├── rate-limiter-redis-outage.md
│   │   ├── kafka-consumer-lag-spike.md
│   │   └── saga-stuck-in-state.md
│   └── slos/
│       └── search-service-slo.md
│
├── platform/                            # shared, versioned starters published to internal Maven repo
│   ├── platform-bom/                    # BOM aligning Spring Boot 4, Kafka clients, OTel, Resilience4j
│   ├── platform-starter-core/           # Spring config base, exception handlers, problem+json, context
│   ├── platform-starter-observability/  # Micrometer + OTel autoconfig, common tags
│   ├── platform-starter-security/       # JWT resource server, RBAC, mTLS helpers
│   ├── platform-starter-kafka/          # producer/consumer config, error handlers, DLQ, outbox relay hook
│   ├── platform-starter-outbox/         # outbox repository, Debezium-friendly writer
│   ├── platform-starter-idempotency/    # Redis+PG idempotency key store
│   ├── platform-starter-rate-limiter/   # Lua + Redis + filter; gateway and service usable
│   └── platform-test-support/           # Testcontainers base classes, Pact base, OTel test harness
│
├── services/
│   ├── api-gateway/
│   │   ├── src/main/java/...
│   │   └── src/main/resources/{application.yml,application-docker.yml}
│   ├── bff-web/                         # BFF for Next.js
│   ├── customer-service/
│   ├── product-service/
│   ├── inventory-service/
│   ├── cart-service/
│   ├── search-service/
│   ├── rating-service/
│   ├── recommendation-service/
│   ├── promotion-service/
│   ├── tax-service/
│   ├── delivery-service/
│   ├── location-service/
│   ├── payment-service/
│   ├── order-orchestrator/              # Spring Statemachine saga + structured concurrency
│   ├── notification-service/
│   └── audit-service/
│
├── contracts/                           # Avro schemas, separate from services for cross-team visibility
│   ├── src/main/avro/
│   │   ├── product/
│   │   │   └── ProductCDC.avsc
│   │   ├── order/
│   │   │   ├── OrderEvent.avsc
│   │   │   ├── SagaCommand.avsc
│   │   │   └── SagaReply.avsc
│   │   ├── payment/PaymentEvent.avsc
│   │   └── ...
│   └── pom.xml                          # avro-maven-plugin generates Java classes
│
├── frontend/
│   └── web/                             # Next.js 15 app
│       ├── app/
│       ├── components/
│       ├── lib/
│       └── package.json
│
├── infrastructure/
│   ├── docker-compose/
│   │   ├── docker-compose.yml
│   │   ├── debezium/
│   │   │   ├── product-connector.json
│   │   │   ├── inventory-connector.json
│   │   │   ├── customer-connector.json
│   │   │   └── register-connectors.sh
│   │   ├── kafka/
│   │   ├── prometheus/prometheus.yml
│   │   ├── grafana/{provisioning,dashboards}
│   │   ├── otel/config.yaml
│   │   ├── tempo/tempo.yaml
│   │   └── loki/local-config.yaml
│   ├── helm/
│   │   ├── platform-common/             # umbrella chart: secrets, mesh policies, RL Redis
│   │   └── services/
│   │       ├── api-gateway/
│   │       ├── customer-service/
│   │       └── ... (one chart per service, all derive from a common template)
│   ├── k8s/
│   │   ├── base/                        # Kustomize base per service
│   │   └── overlays/
│   │       ├── dev/
│   │       ├── staging/
│   │       └── prod/
│   └── terraform/                       # cloud infra (VPC, RDS, MSK, EKS) if not pure-local
│
├── scripts/
│   ├── bootstrap-kafka-topics.sh
│   ├── seed-data.sh                     # seed products, users for local dev
│   ├── register-schemas.sh              # push Avro to Apicurio
│   ├── load-test.sh                     # wraps k6 scenarios
│   └── chaos-experiment.sh              # wraps Chaos Mesh
│
├── tests/
│   ├── contract/                        # Pact contracts shared between consumer/provider
│   ├── e2e/                             # Playwright
│   ├── load/                            # k6 scenarios
│   └── chaos/                           # Chaos Mesh experiments (YAML)
│
└── .github/
    ├── workflows/
    │   ├── ci.yml                       # build + test + scan + image + sign
    │   ├── contract-tests.yml
    │   ├── security-scan.yml            # Trivy, Gitleaks, Snyk
    │   ├── deploy-dev.yml               # ArgoCD sync on merge to main
    │   └── deploy-prod.yml              # manual approval → ArgoCD sync
    └── CODEOWNERS