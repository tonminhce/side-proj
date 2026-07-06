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
 * Mirror of {@link CatalogProductCreatedTest} for the {@code CatalogProductUpdated} record.
 * Same shape on purpose (ADR-15 compat): if a future consumer needs a new field, it lands
 * in BOTH records, never in a divergent shape.
 */
class CatalogProductUpdatedTest {

  @Test
  void record_buildsWithAllFields() {
    CatalogProductUpdated record =
        CatalogProductUpdated.newBuilder()
            .setProductUuid(456L)
            .setSku("blue-shirt")
            .setName("Blue Shirt")
            .setOccurredAt("2026-07-07T02:00:00Z")
            .build();

    assertThat(record.getProductUuid()).isEqualTo(456L);
    assertThat(record.getSku()).isEqualTo("blue-shirt");
    assertThat(record.getName()).isEqualTo("Blue Shirt");
    assertThat(record.getOccurredAt()).isEqualTo("2026-07-07T02:00:00Z");
  }

  @Test
  void record_survivesAvroRoundTrip() throws Exception {
    CatalogProductUpdated original =
        CatalogProductUpdated.newBuilder()
            .setProductUuid(456L)
            .setSku("blue-shirt")
            .setName("Blue Shirt")
            .setOccurredAt("2026-07-07T02:00:00Z")
            .build();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(baos, null);
    new SpecificDatumWriter<>(CatalogProductUpdated.SCHEMA$).write(original, encoder);
    encoder.flush();

    BinaryDecoder decoder =
        DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(baos.toByteArray()), null);
    CatalogProductUpdated decoded =
        (CatalogProductUpdated)
            new SpecificDatumReader<>(CatalogProductUpdated.SCHEMA$).read(null, decoder);

    assertThat(decoded.getProductUuid()).isEqualTo(original.getProductUuid());
    assertThat(decoded.getSku()).isEqualTo(original.getSku());
    assertThat(decoded.getName()).isEqualTo(original.getName());
    assertThat(decoded.getOccurredAt()).isEqualTo(original.getOccurredAt());
  }
}
