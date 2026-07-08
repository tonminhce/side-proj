package vn.vnpt.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class VnAddressCatalogTest {

  private VnAddressCatalog catalog;

  @BeforeEach
  void setUp() {
    catalog = new VnAddressCatalog(new ObjectMapper());
    catalog.load();
  }

  @Test
  void debug_directDeserialization() {
    try (InputStream in = new ClassPathResource("seed/vn-addresses.json").getInputStream()) {
      byte[] bytes = in.readAllBytes();
      VnAddressCatalog.Seed seed = new ObjectMapper().readValue(bytes, VnAddressCatalog.Seed.class);
      System.out.println("DEBUG: seed.provinces=" + (seed.getProvinces() == null ? "NULL" : seed.getProvinces().size()));
      System.out.println("DEBUG: seed.districts=" + (seed.getDistricts() == null ? "NULL" : seed.getDistricts().size()));
      System.out.println("DEBUG: seed.communes=" + (seed.getCommunes() == null ? "NULL" : seed.getCommunes().size()));
    } catch (Exception e) {
      System.out.println("DEBUG: error " + e.getMessage());
    }
  }

  @Test
  void suggestProvinces_returnsMatchesCaseInsensitive() {
    var result = catalog.suggestProvinces("ha noi");
    assertThat(result).isNotEmpty();
  }

  @Test
  void suggestDistrictsWithParent_filtersByParent() {
    var hcmcDistricts = catalog.suggestDistricts("79", "Bình");
    assertThat(hcmcDistricts).isNotEmpty();
  }

  @Test
  void suggestDistrictsWithoutParent_returnsEmpty() {
    var result = catalog.suggestDistricts(null, "Bình");
    assertThat(result).isEmpty();
  }

  @Test
  void suggestReturnsAtMost20Results() {
    var result = catalog.suggestProvinces("");
    assertThat(result.size()).isLessThanOrEqualTo(20);
  }
}