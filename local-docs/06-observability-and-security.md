// 06-observability-and-security.md
# 6. Observability & Security

Production-grade systems require defense-in-depth for security and the "three pillars" of observability.

## Observability Stack (LGTM + Pyroscope)
- **Metrics:** All services expose `/actuator/prometheus` via Micrometer. Custom metrics for rate limiter (allowed/denied by bucket type, latency percentiles).
- **Traces:** OpenTelemetry auto-instrumentation. W3C Trace Context propagated through Kafka headers (`traceparent`). Exported to **Tempo**.
- **Logs:** Exported to **Loki**. Structured JSON logging with trace ID injection for correlation.
- **Profiling:** **Pyroscope** for continuous CPU/allocation profiling to find slow paths without manual heap dumps.
- **Dashboards & SLOs:** Grafana dashboards track per-endpoint p95/p99 latencies, Kafka consumer lag, CDC pipeline lag, and business KPIs. SLOs defined (e.g., "Search p99 < 300ms for 99.5% of 28-day window") with Alertmanager rules burning error budgets at 2x and 14x rates.

## Security
- **North-South (Ingress):** API Gateway terminates TLS. Spring Security Resource Server validates JWTs. A signed device cookie (`rl_did`) mitigates anonymous IP-spoofing in rate limits.
- **East-West (Internal):** Service Mesh (Istio/Linkerd) enforces strict **mTLS** between pods. `AuthorizationPolicy` restricts internal calls (e.g., only Order Orchestrator can call Payment Service internal endpoints).
- **Defense-in-Depth:** JWT validation still happens in each downstream service via Spring Security, trusting the mesh but verifying the token.
- **Secrets:** HashiCorp Vault via External Secrets Operator. Spring Boot `configtree` imports mounted K8s secrets. No plain-text secrets in Git or images.
- **Supply Chain:** CI pipeline uses Trivy/Snyk for CVE scans and Cosign to sign container images.