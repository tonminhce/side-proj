package vn.vnpt.order.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Dev-side HmacServiceKeyProvider for order service — Story 4.1.
 */
class OrderHmacServiceKeyProviderTest {

  @Test
  void devProvider_returnsConsistentSecretAcrossCalls() {
    DevHmacKeyProvider provider = new DevHmacKeyProvider();
    provider.init();
    String s1 = provider.currentSecret();
    String s2 = provider.currentSecret();
    assertThat(s1).isEqualTo(s2);
    assertThat(s1).hasSize(64);  // 32-byte hex
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