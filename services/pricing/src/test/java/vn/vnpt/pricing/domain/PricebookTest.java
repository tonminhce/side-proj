package vn.vnpt.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PricebookTest {

  private Pricebook pricebook;

  @BeforeEach
  void setUp() {
    pricebook = new Pricebook(new ObjectMapper());
    pricebook.load();
  }

  @Test
  void load_parsesValidJson() {
    assertThat(pricebook.get("variant-1")).isPresent();
    assertThat(pricebook.get("variant-2")).isPresent();
    assertThat(pricebook.get("variant-3")).isPresent();
  }

  @Test
  void get_returnsPriceEntryForKnownVariant() {
    PriceEntry e = pricebook.get("variant-1").orElseThrow();
    assertThat(e.listPriceCents()).isEqualTo(1990000L);
    assertThat(e.salePriceCents()).isEqualTo(1790000L);
    assertThat(e.currency()).isEqualTo("VND");
  }

  @Test
  void get_returnsEmptyForUnknownVariant() {
    assertThat(pricebook.get("variant-does-not-exist")).isEmpty();
  }
}