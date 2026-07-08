package vn.vnpt.payment.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the dev-side HmacServiceKeyProvider — Story 3.5 / FR-82 / ADR-20.
 *
 * <p>The vault-backed provider's initial-failure behavior is exercised via a manual subprocess
 * check (no Vault in dev); the dev provider is unit-testable.
 */
class HmacServiceKeyProviderTest {

  @Test
  void devProvider_returnsConsistentSecretAcrossCalls() {
    DevHmacKeyProvider provider = new DevHmacKeyProvider();
    provider.init();
    String s1 = provider.currentSecret();
    String s2 = provider.currentSecret();
    assertThat(s1).isEqualTo(s2);
    assertThat(s1).hasSize(64);  // 32-byte hex = 64 chars
  }

  @Test
  void devProvider_generatesFreshSecretOnEachInitWhenNoEnv() {
    DevHmacKeyProvider a = new DevHmacKeyProvider();
    a.init();
    DevHmacKeyProvider b = new DevHmacKeyProvider();
    b.init();
    assertThat(a.currentSecret()).isNotEqualTo(b.currentSecret());
  }
}