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
 * Round-trip tests for {@link CatalogProductPriceChanged}. The price diff (old + new) is
 * the contract that downstream consumers depend on; pin both values across the
 * serialization boundary.
 */
class CatalogProductPriceChangedTest {

  @Test
  void record_buildsWithAllFields() {
    CatalogProductPriceChanged record =
        CatalogProductPriceChanged.newBuilder()
            .setVariantUuid(789L)
            .setProductUuid(456L)
            .setOldPriceCents(199000L)
            .setNewPriceCents(249000L)
            .setCurrency("VND")
            .setOccurredAt("2026-07-07T03:00:00Z")
            .build();

    assertThat(record.getVariantUuid()).isEqualTo(789L);
    assertThat(record.getProductUuid()).isEqualTo(456L);
    assertThat(record.getOldPriceCents()).isEqualTo(199000L);
    assertThat(record.getNewPriceCents()).isEqualTo(249000L);
    assertThat(record.getCurrency()).isEqualTo("VND");
    assertThat(record.getOccurredAt()).isEqualTo("2026-07-07T03:00:00Z");
  }

  @Test
  void record_survivesAvroRoundTrip() throws Exception {
    CatalogProductPriceChanged original =
        CatalogProductPriceChanged.newBuilder()
            .setVariantUuid(789L)
            .setProductUuid(456L)
            .setOldPriceCents(199000L)
            .setNewPriceCents(249000L)
            .setCurrency("VND")
            .setOccurredAt("2026-07-07T03:00:00Z")
            .build();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(baos, null);
    new SpecificDatumWriter<>(CatalogProductPriceChanged.SCHEMA$).write(original, encoder);
    encoder.flush();

    BinaryDecoder decoder =
        DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(baos.toByteArray()), null);
    CatalogProductPriceChanged decoded =
        (CatalogProductPriceChanged)
            new SpecificDatumReader<>(CatalogProductPriceChanged.SCHEMA$).read(null, decoder);

    assertThat(decoded.getOldPriceCents()).isEqualTo(199000L);
    assertThat(decoded.getNewPriceCents()).isEqualTo(249000L);
  }
}
