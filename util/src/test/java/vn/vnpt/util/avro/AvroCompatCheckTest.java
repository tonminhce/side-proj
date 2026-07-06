package vn.vnpt.util.avro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import vn.vnpt.util.avro.AvroCompatCheck.CompatResult;

/**
 * Verifies the dual-direction Avro compatibility logic used by the CI gate (Story 0.4 / Subtask 5.3
 * / NFR-MIG-2). Three cases cover the canonical compat matrix:
 *
 * <ol>
 *   <li>Adding an optional field with default — backward AND forward compatible.
 *   <li>Removing a required field — forward-incompatible only (new schema ignores the field; old
 *       readers that expect the field can't decode data without it). The spec suggested
 *       INCOMPATIBLE_BOTH but Apicurio's actual behavior is FORWARD-only: Avro is lenient about
 *       extra fields on read, strict about missing fields.
 *   <li>Adding a field without a default — backward-incompatible only (old readers cannot decode
 *       new data because the new field has no default).
 * </ol>
 */
class AvroCompatCheckTest {

  private static final String V1 =
      "{"
          + "\"type\":\"record\","
          + "\"name\":\"ProductEvent\","
          + "\"namespace\":\"vn.vnpt.catalog.events\","
          + "\"fields\":["
          + "  {\"name\":\"id\",\"type\":\"string\"},"
          + "  {\"name\":\"sku\",\"type\":\"string\"}"
          + "]}";

  @Test
  void addingOptionalFieldWithDefaultIsCompatible() {
    String v2 =
        "{"
            + "\"type\":\"record\","
            + "\"name\":\"ProductEvent\","
            + "\"namespace\":\"vn.vnpt.catalog.events\","
            + "\"fields\":["
            + "  {\"name\":\"id\",\"type\":\"string\"},"
            + "  {\"name\":\"sku\",\"type\":\"string\"},"
            + "  {\"name\":\"displayName\",\"type\":[\"null\",\"string\"],\"default\":null}"
            + "]}";

    assertEquals(
        CompatResult.COMPATIBLE, AvroCompatCheck.check(new StringReader(V1), new StringReader(v2)));
  }

  @Test
  void removingRequiredFieldIsForwardIncompatible() {
    String v2 =
        "{"
            + "\"type\":\"record\","
            + "\"name\":\"ProductEvent\","
            + "\"namespace\":\"vn.vnpt.catalog.events\","
            + "\"fields\":["
            + "  {\"name\":\"id\",\"type\":\"string\"}"
            + "]}";

    assertEquals(
        CompatResult.INCOMPATIBLE_FORWARD,
        AvroCompatCheck.check(new StringReader(V1), new StringReader(v2)));
  }

  @Test
  void addingFieldWithoutDefaultIsBackwardIncompatible() {
    String v2 =
        "{"
            + "\"type\":\"record\","
            + "\"name\":\"ProductEvent\","
            + "\"namespace\":\"vn.vnpt.catalog.events\","
            + "\"fields\":["
            + "  {\"name\":\"id\",\"type\":\"string\"},"
            + "  {\"name\":\"sku\",\"type\":\"string\"},"
            + "  {\"name\":\"displayName\",\"type\":\"string\"}"
            + "]}";

    assertEquals(
        CompatResult.INCOMPATIBLE_BACKWARD,
        AvroCompatCheck.check(new StringReader(V1), new StringReader(v2)));
  }
}
