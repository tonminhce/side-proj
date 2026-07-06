---
audience: dev, SRE, PM
project: side-project
date: 2026-07-06
how-to-use: when to use / not use feature flags. How to add + manage + remove. Pair with RISK-REGISTER.md (some flags mitigate risks).
---

# Feature Flags — side-project

> **Goal:** Decouple deployment from release. Allow turning features on/off without redeploy.
> **Convention (per architecture):** NO new feature flag system — use simple env-var flags via Spring Boot's @ConditionalOnProperty. Rollout via Vault + restart.
> **Use flags sparingly.** Every flag is technical debt; remove when no longer needed.

---

## 1. When to use a feature flag

| Use case | Use flag? | Why |
|---|---|---|
| **Decoupling deploy from release** ("ship code dark, turn on later") | ✅ YES | Reduces deploy risk |
| **Canary / phased rollout** (10% → 50% → 100%) | ✅ YES | Limits blast radius |
| **A/B testing** | ⚠️ Maybe | Use a real A/B framework if needed; flags are clunky for this |
| **Kill switch** (turn off broken feature fast) | ✅ YES | Cheap insurance |
| **Risky new feature** (Sprint N introduces something untested at scale) | ✅ YES | Safer to release with kill switch |
| **Per-customer feature** (beta customers, premium tier) | ❌ NO | Use proper feature-management tool, not env var |
| **Test in production** (different code paths for different users) | ❌ NO | Use proper feature-management tool |
| **Permanent configuration** ("which database to use") | ❌ NO | Use config, not flag |

---

## 2. Flag types

| Type | Storage | Toggle mechanism | Use case |
|---|---|---|---|
| **Env var** | K8s ConfigMap / Vault | Restart pod | Sprint-level flags |
| **Vault dynamic secret** | HashiCorp Vault | Read on every request | Real-time toggles |
| **DB column** (per-tenant) | Postgres | Update row, hot-reload | Per-tenant features |

v1 default: **env var** (simplest, no extra infra).

---

## 3. Naming convention

```
ff.<scope>.<feature>.<state>
```

Examples:
- `ff.checkout.use-saga-v2` (use new saga implementation)
- `ff.payment.enable-3ds-stepup` (turn on 3DS for risk-flagged transactions)
- `ff.search.use-vi-analyzer-v2` (new Vietnamese analyzer)
- `ff.feature.kill-switch.{feature}` (per feature kill switch)

Use `.kill-switch.` for emergency stop switches.

---

## 4. Implementation pattern

### Spring Boot @ConditionalOnProperty

```java
@Configuration
public class SagaConfig {

    @Bean
    @ConditionalOnProperty(
        name = "ff.checkout.use-saga-v2",
        havingValue = "true",
        matchIfMissing = false  // default = off
    )
    public SagaImplV2 sagaImplV2() {
        return new SagaImplV2();
    }

    @Bean
    @ConditionalOnMissingBean(SagaImplV2.class)
    public SagaImplV1 sagaImplV1() {
        return new SagaImplV1();
    }
}
```

### Runtime flag (read every call)

```java
@Service
public class PaymentService {
    @Value("${ff.payment.enable-3ds-stepup:false}")
    private boolean enable3dsStepup;

    public PaymentResult charge(Order order) {
        if (order.amountCents > 1_000_000 && enable3dsStepup) {
            return stripe.paymentIntents.confirm(order.paymentIntentId, ...);
        }
        return stripe.paymentIntents.capture(order.paymentIntentId, ...);
    }
}
```

### Kill switch (per feature)

```java
@Service
public class LoyaltyService {
    @Value("${ff.feature.kill-switch.loyalty:false}")
    private boolean loyaltyKilled;

    public void applyPoints(Order order) {
        if (loyaltyKilled) {
            log.warn("Loyalty disabled by feature flag; skipping");
            return;
        }
        // ... normal flow
    }
}
```

---

## 5. Flag lifecycle

```
1. ADD flag (default: OFF)
     ↓
2. SHIP code (flag OFF, code path tested in CI)
     ↓
3. ENABLE in staging (testing)
     ↓
4. ENABLE in prod (canary 10% → 50% → 100%)
     ↓
5. REMOVE flag + code path (when stable)
```

### Time-to-remove target

- **<30 days** for new features (still in iteration)
- **<90 days** for kill switches (after the bug is gone)
- **Never** for permanent config (shouldn't be a flag)

---

## 6. Where to store flags

### In code (env-var only)

```yaml
# application.yml
ff:
  checkout:
    use-saga-v2: ${FF_CHECKOUT_USE_SAGA_V2:false}
  payment:
    enable-3ds-stepup: ${FF_PAYMENT_ENABLE_3DS_STEPUP:false}
```

### In K8s

```yaml
# k8s/prod/deployment.yaml
env:
  - name: FF_CHECKOUT_USE_SAGA_V2
    value: "true"
  - name: FF_PAYOUT_ENABLE_3DS_STEPUP
    value: "false"
```

### In Vault (for runtime-toggled secrets / flags)

```bash
# In a Vault path, e.g. secret/ff/
vault kv put secret/ff/checkout.use-saga-v2 value="true"

# In service config
spring:
  cloud:
    vault:
      kv:
        enabled: true
        backend: secret
        default-context: ff
```

---

## 7. Flag governance

### Catalog (track all flags)

`platform/feature-flags/flags.yaml`:

```yaml
flags:
  - name: ff.checkout.use-saga-v2
    owner: checkout-team
    created: 2026-08-01
    purpose: Toggle for new saga state machine
    default: false
    state: enabled-staging
    expires_after: 2026-09-01  # remove by this date
    linked_story: 2.5

  - name: ff.payment.enable-3ds-stepup
    owner: payment-team
    created: 2026-08-15
    purpose: Turn on 3DS for high-value transactions
    default: false
    state: enabled-prod
    expires_after: 2026-11-15
    linked_story: 3.5
```

### Review cadence

- **Weekly** (during sprint): review flags, decide to remove or extend
- **At every release**: check `flags.yaml`, remove expired flags

---

## 8. Sprint-level flags for side-project

Pre-defined flags for known risks:

| Flag | Default | State | Owner | Sprint |
|---|---|---|---|---|
| `ff.checkout.use-saga-v2` | false | off (v1 uses Modulith outbox saga per ADR-01) | epic 2 | Sprint 2 |
| `ff.payment.enable-3ds-stepup` | false | off (manual trigger per FR-27) | epic 3 | Sprint 3 |
| `ff.inventory.use-multi-warehouse` | false | off (v1 single-warehouse per ADR-06) | epic 1 | Sprint 1 |
| `ff.invoice.fail-fast-without-credentials` | true | ON (Q5 closure / Story 9.2 fail-fast check) | epic 9 | Sprint 9 |
| `ff.feature.kill-switch.checkout` | false | off (turn ON if checkout has Sev-0 incident) | on-call | always |
| `ff.feature.kill-switch.payment` | false | off | on-call | always |
| `ff.feature.kill-switch.invoice` | false | off (turn ON if VN tax-invoice misbehaves) | on-call | always |

---

## 9. Kill switch playbook

When a Sev-0/1 incident affects a specific feature:

```bash
# 1. Set kill switch via Vault (no redeploy needed)
vault kv put secret/ff/feature.kill-switch.{feature} value="true"

# 2. Force service reload (or wait for periodic refresh)
kubectl rollout restart deployment/<service> -n side-project

# 3. Verify in logs
kubectl logs -n side-project -l app=<service> --tail=100 | grep "feature.*killed"

# 4. Communicate (per BUG-TRIAGE.md)
```

To disable kill switch (re-enable feature):

```bash
vault kv put secret/ff/feature.kill-switch.{feature} value="false"
kubectl rollout restart deployment/<service> -n side-project
```

---

## 10. Common pitfalls

### ❌ Pitfall 1: "I'll add a flag for this later"

```java
// ❌ WRONG: Hidden feature flag
if (System.getenv("FF_NEW_THING") != null && System.getenv("FF_NEW_THING").equals("true")) {
    doNewThing();
} else {
    doOldThing();
}
// This is technical debt you forgot to document.

// ✅ RIGHT: Either commit to using a flag (and document it) or don't
```

### ❌ Pitfall 2: "Flags are forever"

```java
// ❌ WRONG: Flag from 2024 still here in 2026
@Value("${ff.feature.old-thing:false}")  // what does this even do?
```

Every flag has an `expires_after` date. After that date, remove the flag and the dead code path.

### ❌ Pitfall 3: "Flags are not tested in CI"

```java
// ❌ WRONG: Code path only tested when flag is on
if (flagX) { newPath(); } else { oldPath(); }
// CI only tests oldPath; newPath is unverified in prod
```

Every flag has a test that exercises BOTH paths (on + off).

### ❌ Pitfall 4: "Two features share a flag"

```java
// ❌ WRONG: One flag for two unrelated features
@Value("${ff.checkout-and-payment}")
// What does this mean? Now you can't turn on payment without checkout.
```

One flag per feature. Period.

### ❌ Pitfall 5: "Flag is in production with no expiration"

```yaml
# ❌ WRONG
- name: ff.checkout.new-flow
  state: enabled-prod
  # no expires_after

# ✅ RIGHT
- name: ff.checkout.new-flow
  state: enabled-prod
  expires_after: 2026-09-01  # remove by this date
```

---

## 11. When NOT to use a flag

### ❌ Don't use flags for:

- **Permanent config** (use config / Vault / DB column)
- **A/B testing at scale** (use a real A/B platform like LaunchDarkly)
- **Per-tenant features** (use a feature-management platform with tenant targeting)
- **Environment differences** (dev vs staging vs prod) — use environment-specific config, not flags
- **Bug workarounds** (just fix the bug; the flag is technical debt)
- **Cosmetic changes** (just ship the cosmetic change)

### ✅ Use flags for:

- **Decoupling deploy from release** (e.g., Sprint 1 ships code dark, enables after testing)
- **Canary / phased rollout** (10% → 50% → 100% over a sprint)
- **Kill switch** (emergency stop for a broken feature)
- **Risky new feature** (Sprint introduces something untested at scale)

---

## 12. Checklist: adding a new flag

- [ ] Is this actually a flag, or just config? If config, use config.
- [ ] Add flag to `flags.yaml` with: name, owner, created, purpose, default, state, expires_after, linked_story
- [ ] Add env-var to relevant service `application.yml` (with safe default)
- [ ] Add K8s deployment entry (default false; explicit true when needed)
- [ ] Implement code with @ConditionalOnProperty or @Value
- [ ] Add CI test that exercises BOTH flag states (on + off)
- [ ] Add to dashboard: "Flag X is currently: on" (or alert when flag changes)
- [ ] Communicate to team in Slack: "Added flag ff.X"
- [ ] Set a calendar reminder to remove the flag at expires_after

---

## 13. Checklist: removing a flag

- [ ] Is the flag still in flags.yaml? If yes, confirm the dependent code is no longer needed.
- [ ] Update the code: remove the @ConditionalOnProperty branch, use the always-active path.
- [ ] Update tests: remove the flag-on / flag-off test; add a test for the always-active path.
- [ ] Update configuration: remove the env-var from application.yml and K8s.
- [ ] Update flags.yaml: remove the entry.
- [ ] Communicate to team: "Removed flag ff.X, code path always active now"
- [ ] Verify in staging: feature still works as expected.
- [ ] Verify in prod: deploy, monitor for 1 week.

---

## 14. Cross-references

- **Operational runbook:** `DEVOPS-RUNBOOK.md` (for env-var / Vault setup)
- **Risk register:** `RISK-REGISTER.md` (some flags mitigate risks)
- **Sprint work:** `SPRINT-1-DEV-HANDBOOK.md` + `EPIC-1-STORIES-QUICKREF.md`
- **PR conventions:** `CONTRIBUTING.md` (CI checks for flag safety)
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
