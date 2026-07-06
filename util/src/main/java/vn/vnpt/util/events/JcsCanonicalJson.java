package vn.vnpt.util.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JCS (RFC 8785) canonical JSON serializer for event envelopes.
 *
 * <p>Why hand-rolled: the RFC 8785 reference impl is ~100KB of transitive deps (jcs Maven
 * artifact) and Jackson's canonical output is NOT byte-deterministic across versions. The
 * behavior we need — sort keys lex, encode numbers/booleans/nulls in JSON form, no whitespace
 * — is ~80 lines of Java.
 *
 * <p>Determinism invariants:
 *
 * <ul>
 *   <li>Object keys sorted lexicographically (per RFC 8785 §3.3).
 *   <li>Numbers serialized as JSON numbers (no quotes).
 *   <li>Booleans serialized as {@code true}/{@code false} (NOT {@code 1}/{@code 0}).
 *   <li>Strings JSON-escaped (control chars, {@code "}, {@code \}).
 *   <li>Array order preserved (RFC 8785 does NOT specify array sort).
 *   <li>No whitespace.
 * </ul>
 *
 * <p>Unsupported types: BigInteger/BigDecimal serialize via {@code toString()}; this is
 * non-canonical per RFC 8785 §3.2.2 (which requires fixed-precision serialization). Story 1.3
 * callers do not use those types, so the gap is acceptable; a future hardening story can
 * extend the number branch.
 */
public final class JcsCanonicalJson {

  private JcsCanonicalJson() {}

  /**
   * Serialize a {@code Map<String, Object>} envelope to a JCS-canonical byte sequence.
   *
   * @throws IllegalArgumentException for unsupported scalar types (anything not null, Boolean,
   *     Number, String, Map, List)
   */
  public static String serialize(Map<String, Object> envelope) {
    StringBuilder sb = new StringBuilder();
    writeObject(envelope, sb);
    return sb.toString();
  }

  private static void writeObject(Map<?, ?> map, StringBuilder sb) {
    // RFC 8785 §3.3 — keys sorted lexicographically. Using TreeMap for natural String ordering
    // (null keys are not allowed; envelope keys are always String).
    Map<String, Object> sorted = new TreeMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      sorted.put(String.valueOf(e.getKey()), e.getValue());
    }
    sb.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> e : sorted.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      writeString(e.getKey(), sb);
      sb.append(':');
      writeValue(e.getValue(), sb);
    }
    sb.append('}');
  }

  private static void writeArray(List<?> list, StringBuilder sb) {
    sb.append('[');
    boolean first = true;
    for (Object item : list) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      writeValue(item, sb);
    }
    sb.append(']');
  }

  @SuppressWarnings("unchecked")
  private static void writeValue(Object value, StringBuilder sb) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Boolean b) {
      sb.append(b ? "true" : "false");
    } else if (value instanceof Number n) {
      // RFC 8785 §3.2.2 — for Story 1.3, the envelope carries Long / Double / Integer only.
      // BigDecimal/BigInteger are non-canonical; they would need scale/sign handling.
      sb.append(n.toString());
    } else if (value instanceof String s) {
      writeString(s, sb);
    } else if (value instanceof Map<?, ?> m) {
      writeObject(m, sb);
    } else if (value instanceof List<?> l) {
      writeArray(l, sb);
    } else {
      throw new IllegalArgumentException(
          "Unsupported JCS value type: " + value.getClass().getName());
    }
  }

  private static void writeString(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }

  /**
   * Helper: build a {@code LinkedHashMap} (deterministic iteration order matches insertion order,
   * so {@code TreeMap} sort at write time is the only thing that matters for output).
   */
  public static Map<String, Object> newEnvelope() {
    return new LinkedHashMap<>();
  }

  /** Helper: returns a mutable {@code ArrayList}. */
  public static List<Object> newArrayList() {
    return new ArrayList<>();
  }
}
