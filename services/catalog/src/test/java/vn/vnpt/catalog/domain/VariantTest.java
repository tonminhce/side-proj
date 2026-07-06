package vn.vnpt.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit tests for {@link Variant} domain logic — no Spring context.
 *
 * <p>Subtask 9.1 — verifies the SKU stability invariant from AC #3 and Subtask 4.6.
 */
class VariantTest {

  /** Same inputs across two computeSku calls (each call instantiates its own MessageDigest). */
  @Test
  void sku_isStableAcrossInstances() {
    String a = Variant.computeSku("red-shirt", Map.of("color", "red", "size", "M"));
    String b = Variant.computeSku("red-shirt", Map.of("color", "red", "size", "M"));
    assertThat(a).isEqualTo(b);
  }

  /** Map iteration order is not part of the contract — different input orders must yield the same SKU. */
  @Test
  void sku_isOrderIndependent() {
    Map<String, String> alphabetical = new LinkedHashMap<>();
    alphabetical.put("color", "red");
    alphabetical.put("size", "M");

    Map<String, String> reverse = new LinkedHashMap<>();
    reverse.put("size", "M");
    reverse.put("color", "red");

    assertThat(Variant.computeSku("red-shirt", alphabetical))
        .isEqualTo(Variant.computeSku("red-shirt", reverse));
  }

  @Test
  void sku_changesWithValue() {
    String a = Variant.computeSku("red-shirt", Map.of("color", "red"));
    String b = Variant.computeSku("red-shirt", Map.of("color", "blue"));
    assertThat(a).isNotEqualTo(b);
  }

  /** Empty attribute map hashes the canonical empty string — precomputed constant pinned here. */
  @Test
  void sku_withEmptyAttributes_isDeterministic() {
    String a = Variant.computeSku("red-shirt", Map.of());
    String b = Variant.computeSku("red-shirt", Map.of());
    // SHA-256("") hex first 16 chars: e3b0c44298fc1c14
    assertThat(a).isEqualTo("red-shirt-e3b0c44298fc1c14");
    assertThat(a).isEqualTo(b);
  }

  @Test
  void sku_prefixIsProductSlug() {
    String sku = Variant.computeSku("blue-jeans", Map.of("color", "blue"));
    assertThat(sku).startsWith("blue-jeans-");
  }

  /**
   * Stability invariant extension: the canonical form must produce a byte-identical hash across
   * re-implementations. Pin the precomputed SHA-256 hex prefix so a future "optimization" of
   * {@link Variant#computeSku} (e.g. switching to a faster digest, dropping the {@code |} separator)
   * breaks this test loudly. Recomputed via {@code printf 'color=red|size=M' | shasum -a 256}.
   */
  @Test
  void sku_producesExpectedHashForKnownInput() {
    String sku = Variant.computeSku("red-shirt", Map.of("color", "red", "size", "M"));
    assertThat(sku).isEqualTo("red-shirt-3e59e30d7769a8a7");
  }

  /**
   * Hex suffix is exactly 16 chars (64 bits of SHA-256) — pin the length so a future truncation
   * tweak is caught. Also pins the lowercase-hex format.
   */
  @Test
  void sku_hashSuffixIsSixteenLowercaseHexChars() {
    String sku = Variant.computeSku("red-shirt", Map.of("color", "red", "size", "M"));
    String suffix = sku.substring("red-shirt-".length());
    assertThat(suffix).hasSize(16).matches("[0-9a-f]{16}");
  }
}
