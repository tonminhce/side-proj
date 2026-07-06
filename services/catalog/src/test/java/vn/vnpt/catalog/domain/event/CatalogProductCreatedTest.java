package vn.vnpt.catalog.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.Test;

/**
 * Avro-generated POJO round-trip + builder tests (Story 1.3 / AC #4, #12).
 *
 * <p>The generated {@code CatalogProductCreated} is a {@link
 * org.apache.avro.specific.SpecificRecord}. Two invariants are pinned:
 *
 * <ol>
 *   <li>Builder fluent API round-trips the field values (sanity check on the codegen).
 *   <li>Binary {@link org.apache.avro.io.DatumWriter}/{@link
 *       org.apache.avro.io.DatumReader} round-trip preserves all fields (proves the
 *       {@code .avsc} schema and the generated Java are consistent — a future schema change
 *       that breaks the codegen will surface here as a deserialization error).
 * </ol>
 */
class CatalogProductCreatedTest {

  @Test
  void record_buildsWithAllFields() {
    CatalogProductCreated record =
        CatalogProductCreated.newBuilder()
            .setProductUuid(123L)
            .setSku("red-shirt")
            .setName("Red Shirt")
            .setOccurredAt("2026-07-07T01:00:00Z")
            .build();

    assertThat(record.getProductUuid()).isEqualTo(123L);
    assertThat(record.getSku()).isEqualTo("red-shirt");
    assertThat(record.getName()).isEqualTo("Red Shirt");
    assertThat(record.getOccurredAt()).isEqualTo("2026-07-07T01:00:00Z");
  }

  @Test
  void record_survivesAvroRoundTrip() throws Exception {
    CatalogProductCreated original =
        CatalogProductCreated.newBuilder()
            .setProductUuid(123L)
            .setSku("red-shirt")
            .setName("Red Shirt")
            .setOccurredAt("2026-07-07T01:00:00Z")
            .build();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(baos, null);
    new SpecificDatumWriter<>(CatalogProductCreated.SCHEMA$).write(original, encoder);
    encoder.flush();

    BinaryDecoder decoder =
        DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(baos.toByteArray()), null);
    CatalogProductCreated decoded =
        (CatalogProductCreated)
            new SpecificDatumReader<>(CatalogProductCreated.SCHEMA$).read(null, decoder);

    assertThat(decoded.getProductUuid()).isEqualTo(original.getProductUuid());
    assertThat(decoded.getSku()).isEqualTo(original.getSku());
    assertThat(decoded.getName()).isEqualTo(original.getName());
    assertThat(decoded.getOccurredAt()).isEqualTo(original.getOccurredAt());
  }
}
