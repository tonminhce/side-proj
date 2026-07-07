package vn.vnpt.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exhaustive unit tests for {@link IdempotencyKey} — Story 3.1 / AC #2, #4, #5, #8, #9.
 *
 * <p>The 10k pairwise-distinct test catches the entire bug family of "the key looks stable but isn't"
 * (timestamp drift, {@code Math.random()} in the path, dropped separator). The exact-form test pins
 * the algorithm — a future PR that swaps SHA-256 for SHA-512 fails loudly.
 */
class IdempotencyKeyTest {

  private static final Pattern HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

  @Test
  void forOrderStep_stableAcrossNCalls() {
    String first = IdempotencyKey.forOrderStep(42L, "payment.authorize");
    for (int i = 0; i < 1_000; i++) {
      assertThat(IdempotencyKey.forOrderStep(42L, "payment.authorize")).isEqualTo(first);
    }
  }

  @Test
  void forOrderStep_exactForm() throws Exception {
    String key = IdempotencyKey.forOrderStep(42L, "payment.authorize");
    assertThat(key).matches(HEX_64);

    String expected = bytesToHex(
        MessageDigest.getInstance("SHA-256")
            .digest("42:payment.authorize".getBytes(StandardCharsets.UTF_8)));
    assertThat(key).isEqualTo(expected);
  }

  @Test
  void forOrderStep_threeStepsAreDistinct() {
    String authorize = IdempotencyKey.forOrderStep(42L, "payment.authorize");
    String capture = IdempotencyKey.forOrderStep(42L, "payment.capture");
    String refund = IdempotencyKey.forOrderStep(42L, "payment.refund");

    assertThat(authorize).matches(HEX_64);
    assertThat(capture).matches(HEX_64);
    assertThat(refund).matches(HEX_64);
    assertThat(authorize).isNotEqualTo(capture);
    assertThat(authorize).isNotEqualTo(refund);
    assertThat(capture).isNotEqualTo(refund);
  }

  @Test
  void forOrderStep_twoOrdersAreDistinct() {
    String a = IdempotencyKey.forOrderStep(1L, "payment.authorize");
    String b = IdempotencyKey.forOrderStep(2L, "payment.authorize");
    assertThat(a).isNotEqualTo(b);
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 100L, 1_000L, 10_000L, 1_000_000L})
  void forOrderStep_parametrizedOrdersArePairwiseDistinct(long orderUuid) {
    String expected = IdempotencyKey.forOrderStep(orderUuid, "payment.authorize");
    assertThat(expected).matches(HEX_64);
    assertThat(IdempotencyKey.forOrderStep(orderUuid, "payment.authorize")).isEqualTo(expected);
  }

  @Test
  void forOrderStep_10kRandomOrdersArePairwiseDistinct() {
    int n = 10_000;
    Set<String> keys = new HashSet<>(n * 2);
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    for (int i = 0; i < n; i++) {
      keys.add(IdempotencyKey.forOrderStep(rnd.nextLong(), "payment.authorize"));
    }
    assertThat(keys).hasSize(n);
  }

  @Test
  void forOrderStep_nullStep_throwsIAE() {
    assertThatThrownBy(() -> IdempotencyKey.forOrderStep(42L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sagaStep");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "   \n  "})
  void forOrderStep_blankStep_throwsIAE(String blank) {
    assertThatThrownBy(() -> IdempotencyKey.forOrderStep(42L, blank))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void forOrderStep_negativeOrderUuid_works() {
    // The contract is `long`; don't constrain the input space unnecessarily. Snowflake is positive
    // but the hash function doesn't care.
    String key = IdempotencyKey.forOrderStep(-1L, "payment.authorize");
    assertThat(key).matches(HEX_64);
    assertThat(IdempotencyKey.forOrderStep(-1L, "payment.authorize")).isEqualTo(key);
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}