# ADR-19 / NFR-SEC-3: unit tests for kafka-topic-creation policy.
# Run with: `opa test platform/policies/opa/`
# Each test uses the `with input as` directive to feed a topic request and
# asserts the deny set membership.
#
# OPA 1.x requires the `if` keyword in rule bodies (see story 0.3 Debug Log).

package kafka.admission

# ---- Negative cases: must be DENIED ----

test_deny_when_no_retention_ms_and_no_retention_bytes if {
  deny["topic test must declare retention.ms or retention.bytes (ADR-19)"]
  with input as {"topic": {"name": "test", "retention_ms": null, "retention_bytes": null}}
}

test_deny_message_includes_topic_name if {
  deny["topic orders must declare retention.ms or retention.bytes (ADR-19)"]
  with input as {"topic": {"name": "orders", "retention_ms": null, "retention_bytes": null}}
}

# ---- Positive cases: must be ALLOWED ----

test_allow_when_retention_ms_set if {
  count(deny) == 0
  with input as {"topic": {"name": "orders", "retention_ms": "604800000", "retention_bytes": null}}
}

test_allow_when_retention_bytes_set if {
  count(deny) == 0
  with input as {"topic": {"name": "audit", "retention_ms": null, "retention_bytes": "1073741824"}}
}

test_allow_when_both_set if {
  count(deny) == 0
  with input as {"topic": {"name": "cdc.products.product", "retention_ms": "604800000", "retention_bytes": "1073741824"}}
}
