package vn.vnpt.util.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * JCS canonical JSON tests (Story 1.3 / AC #7 / ADR-20 / RFC 8785).
 *
 * <p>Pins the determinism invariants the spec calls out:
 *
 * <ul>
 *   <li>Object keys sorted lexicographically.
 *   <li>Null serialized as the JSON literal {@code null} (not the string {@code "null"}).
 *   <li>Booleans serialized as {@code true}/{@code false} (not {@code 1}/{@code 0}).
 *   <li>Strings JSON-escaped (control chars, {@code "}, {@code \}).
 *   <li>Nested maps handled recursively.
 * </ul>
 */
class JcsCanonicalJsonTest {

  @Test
  void serializesKeysInLexicographicOrder() {
    String out = JcsCanonicalJson.serialize(Map.of("z", 1, "a", 2));
    // 'a' must come before 'z' regardless of input map iteration order.
    assertThat(out).startsWith("{\"a\":2,\"z\":1}");
  }

  @Test
  void serializesNullAsLiteral() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("x", null);
    String out = JcsCanonicalJson.serialize(m);
    assertThat(out).isEqualTo("{\"x\":null}");
  }

  @Test
  void serializesBooleanAsJsonBool() {
    String out = JcsCanonicalJson.serialize(Map.of("x", true));
    assertThat(out).isEqualTo("{\"x\":true}");
    String outFalse = JcsCanonicalJson.serialize(Map.of("x", false));
    assertThat(outFalse).isEqualTo("{\"x\":false}");
  }

  @Test
  void serializesStringWithJsonEscape() {
    String out = JcsCanonicalJson.serialize(Map.of("x", "a\"b\\c\n"));
    // a -> a; " -> \"; \ -> \\; newline -> \n
    assertThat(out).isEqualTo("{\"x\":\"a\\\"b\\\\c\\n\"}");
  }

  @Test
  void serializesNestedMapRecursively() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("k", "v");
    Map<String, Object> outer = new LinkedHashMap<>();
    outer.put("inner", inner);
    String out = JcsCanonicalJson.serialize(outer);
    assertThat(out).isEqualTo("{\"inner\":{\"k\":\"v\"}}");
  }

  @Test
  void serializesListInInputOrder() {
    String out = JcsCanonicalJson.serialize(Map.of("xs", List.of(1, 2, 3)));
    assertThat(out).isEqualTo("{\"xs\":[1,2,3]}");
  }

  @Test
  void rejectsUnsupportedValueType() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("weird", new Object());
    assertThatThrownBy(() -> JcsCanonicalJson.serialize(m))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
