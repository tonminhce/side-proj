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

/** Round-trip tests for the {@code CatalogProductDeleted} event. */
class CatalogProductDeletedTest {

  @Test
  void record_buildsWithAllFields() {
    CatalogProductDeleted record =
        CatalogProductDeleted.newBuilder()
            .setProductUuid(111L)
            .setSku("retired-shirt")
            .setOccurredAt("2026-07-07T04:00:00Z")
            .build();

    assertThat(record.getProductUuid()).isEqualTo(111L);
    assertThat(record.getSku()).isEqualTo("retired-shirt");
    assertThat(record.getOccurredAt()).isEqualTo("2026-07-07T04:00:00Z");
  }

  @Test
  void record_survivesAvroRoundTrip() throws Exception {
    CatalogProductDeleted original =
        CatalogProductDeleted.newBuilder()
            .setProductUuid(111L)
            .setSku("retired-shirt")
            .setOccurredAt("2026-07-07T04:00:00Z")
            .build();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(baos, null);
    new SpecificDatumWriter<>(CatalogProductDeleted.SCHEMA$).write(original, encoder);
    encoder.flush();

    BinaryDecoder decoder =
        DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(baos.toByteArray()), null);
    CatalogProductDeleted decoded =
        (CatalogProductDeleted)
            new SpecificDatumReader<>(CatalogProductDeleted.SCHEMA$).read(null, decoder);

    assertThat(decoded.getProductUuid()).isEqualTo(original.getProductUuid());
    assertThat(decoded.getSku()).isEqualTo(original.getSku());
    assertThat(decoded.getOccurredAt()).isEqualTo(original.getOccurredAt());
  }
}
