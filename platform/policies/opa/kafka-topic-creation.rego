# ADR-19 / NFR-SEC-3: every Kafka topic must declare retention.ms or retention.bytes.
# Sprint 0 enforces this via OPA-loaded policy + smoke test. Runtime enforcement
# (Kafka admin -> OPA HTTP) lands in Story 10.3.

package kafka.admission

deny[msg] if {
  input.topic.retention_ms == null
  input.topic.retention_bytes == null
  msg := sprintf("topic %v must declare retention.ms or retention.bytes (ADR-19)", [input.topic.name])
}
