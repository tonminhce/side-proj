# Story 10.3 fills in the real schema-admission policy. For Sprint 0 the stub
# allows everything; this test pins the stub's current contract so the next
# story's tightening is detectable (allow -> conditional allow).
#
# OPA 1.x requires the `if` keyword in rule bodies (see story 0.3 Debug Log).

package schema.admission

test_allow_is_true if {
  allow == true
}
