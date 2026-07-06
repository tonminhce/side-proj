// 01-architecture-overview.md
# 1. Architecture Overview

The system utilizes a database-per-service pattern with asynchronous communication via Apache Kafka. An API Gateway serves as the single entry point for cross-cutting concerns, while a Backend-for-Frontend (BFF) aggregates data for the Next.js frontend.

## Textual Architecture Diagram

```text
                            ┌────────────────────────────────────┐
                            │           Clients                  │
                            │  Web (Next.js 15)  ·  Mobile · Admin│
                            └───────────────┬────────────────────┘
                                            │ HTTPS
                                            ▼
                            ┌────────────────────────────────────┐
                            │       API Gateway (SCG)            │
                            │  Auth · RateLimiter · CB · Routing │
                            │  Virtual threads, Lua+Redis RL     │
                            └───────┬───────────────┬────────────┘
                                    │               │ mTLS (mesh)
            ┌───────────────────────┴───────────────┴───────────────────────┐
            │                    Service Mesh (Istio/Linkerd)                │
            │   ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐│
            │   │ BFF Web │ │Customer │ │ Product │ │Inventory│ │  Cart   ││
            │   └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘│
            │        │          │           │           │           │      │
            │   ┌────┴────┐ ┌───┴────┐ ┌────┴────┐ ┌────┴────┐ ┌────┴────┐ │
            │   │ Search  │ │ Rating │ │  Promo  │ │  Tax    │ │Location │ │
            │   │  (ES)   │ │        │ │         │ │         │ │         │ │
            │   └────┬────┘ └────┬───┘ └────┬────┘ └────┬────┘ └────┬────┘ │
            │        │           │          │           │           │      │
            │   ┌────┴───────────┴──────────┴───────────┴───────────┴────┐ │
            │   │ Order Orchestrator (Saga) · Payment · Delivery         │ │
            │   │ Notification · Recommendation · Audit                  │ │
            │   └────┬───────────────────────────────────────────────────┘ │
            └────────┼─────────────────────────────────────────────────────┘
                     │
        ┌────────────┼────────────────────────────────────────────────┐
        │            │                Event Backbone                    │
        │   ┌────────▼────────┐   ┌──────────────┐   ┌──────────────┐ │
        │   │  Kafka Cluster  │◀──│  Debezium    │──▶│ Schema Reg.  │ │
        │   │  (K Raft, SSL)  │   │  Connect     │   │ (Apicurio)   │ │
        │   └────────┬────────┘   └──────┬───────┘   └──────────────┘ │
        │            │                   │                              │
        │   ┌────────▼─────┐   ┌─────────▼─────────┐                   │
        │   │   Topics     │   │ Per-service PG DBs │                   │
        │   │ (compact/lr) │   │ (DB-per-service)   │                   │
        │   └──────────────┘   └────────────────────┘                   │
        │                                                              │
        │   ┌────────────┐  ┌──────────┐  ┌────────────┐  ┌─────────┐ │
        │   │ Redis (RL)│  │ ES 8.x   │  │ Vault      │  │ MinIO/S3│ │
        │   │ Cluster    │  │ Cluster  │  │ (Secrets)  │  │ (Media) │ │
        │   └────────────┘  └──────────┘  └────────────┘  └─────────┘ │
        └──────────────────────────────────────────────────────────────┘

        Observability: OTel → Tempo (traces) · Prometheus (metrics) → Grafana
                       Loki (logs) · Pyroscope (profiles) · Alertmanager
        GitOps: ArgoCD → K8s (dev/staging/prod), Helm + Kustomize overlays