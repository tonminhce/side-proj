// README.md
# Ecommerce Platform Architecture

This documentation outlines the architecture for a production-grade, event-driven microservice ecommerce platform built with Java 25 and Spring Boot 4.

This project serves as a reference implementation demonstrating modern microservice patterns, event-driven architecture, CDC, and custom infrastructure components.

## Table of Contents
1. [Architecture Overview](./01-architecture-overview.md)
2. [Architecture Decisions & Fixes](./02-architecture-decisions-and-fixes.md)
3. [Kafka & CDC Strategy](./03-kafka-and-cdc-strategy.md)
4. [Rate Limiter Design](./04-rate-limiter-design.md)
5. [Saga & Checkout Flow](./05-saga-and-checkout-flow.md)
6. [Observability & Security](./06-observability-and-security.md)
7. [Implementation Roadmap](./07-implementation-roadmap.md)
8. [Infrastructure & Deployment](./08-infrastructure-and-deployment.md)
9. [Project Structure](./09-project-structure.md)

## Core Tooling
- **Backend:** Java 25, Spring Boot 4, Spring Cloud Gateway, Spring Security, Spring Data JPA, Spring Kafka, Spring Statemachine
- **Frontend:** Next.js 15
- **Messaging:** Apache Kafka (KRaft mode)
- **CDC:** Debezium Connect with Outbox Event Router SMT
- **Schema:** Apicurio Registry (Avro)
- **Search:** Elasticsearch 8.x
- **State:** Redis (centralized token buckets)
- **Observability:** OpenTelemetry, Prometheus, Grafana, Loki, Tempo