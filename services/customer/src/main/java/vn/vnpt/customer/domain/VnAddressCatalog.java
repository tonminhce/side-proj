package vn.vnpt.customer.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import vn.vnpt.util.common.unit.CommuneDto;
import vn.vnpt.util.common.unit.DistrictDto;
import vn.vnpt.util.common.unit.ProvinceDto;

/**
 * Vietnamese address catalog — Story 5.3 / FR-48. Loads a static seed at startup + provides
 * case-insensitive, diacritic-folded prefix matching. Backed by an in-memory map; the
 * future ES wiring is a 1-line swap of the data source.
 */
@Component
public class VnAddressCatalog {

  private static final Logger log = LoggerFactory.getLogger(VnAddressCatalog.class);
  private static final int MAX_RESULTS = 20;

  private final ObjectMapper objectMapper;

  private List<ProvinceDto> provinces = List.of();
  private Map<String, List<DistrictDto>> districts = Map.of();
  private Map<String, List<CommuneDto>> communes = Map.of();

  public VnAddressCatalog(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void load() {
    try (InputStream in = new ClassPathResource("seed/vn-addresses.json").getInputStream()) {
      Seed seed = objectMapper.readValue(in, Seed.class);
      this.provinces = seed.provinces == null ? List.of() : List.copyOf(seed.provinces);
      this.districts = seed.districts == null ? Map.of() : Map.copyOf(seed.districts);
      this.communes = seed.communes == null ? Map.of() : Map.copyOf(seed.communes);
      log.info("VnAddressCatalog loaded: {} provinces, {} district groups, {} commune groups",
          provinces.size(), districts.size(), communes.size());
    } catch (Exception e) {
      log.error("Failed to load vn-addresses.json", e);
    }
    // Debug fallback: re-load from filesystem if classpath resolution failed.
    if (provinces.isEmpty()) {
      try {
        java.io.File f = new java.io.File("src/main/resources/seed/vn-addresses.json");
        if (f.exists()) {
          Seed seed = objectMapper.readValue(f, Seed.class);
          this.provinces = List.copyOf(seed.provinces);
          this.districts = Map.copyOf(seed.districts);
          this.communes = Map.copyOf(seed.communes);
        }
      } catch (Exception e) {
        // ignore
      }
    }
  }

  public List<ProvinceDto> suggestProvinces(String q) {
    if (q == null || q.isBlank()) return List.of();
    String needle = fold(q);
    return provinces.stream()
        .filter(p -> fold(p.getProvinceName()).contains(needle))
        .limit(MAX_RESULTS)
        .toList();
  }

  public List<DistrictDto> suggestDistricts(String parent, String q) {
    if (parent == null || parent.isBlank() || q == null || q.isBlank()) return List.of();
    String needle = fold(q);
    return districts.getOrDefault(parent, List.of()).stream()
        .filter(d -> fold(d.getDistrictName()).contains(needle))
        .limit(MAX_RESULTS)
        .toList();
  }

  public List<CommuneDto> suggestCommunes(String parent, String q) {
    if (parent == null || parent.isBlank() || q == null || q.isBlank()) return List.of();
    String needle = fold(q);
    return communes.getOrDefault(parent, List.of()).stream()
        .filter(c -> fold(c.getCommuneName()).contains(needle))
        .limit(MAX_RESULTS)
        .toList();
  }

  private static String fold(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFD)
        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
        .toLowerCase();
  }

  @Data
  public static class Seed {
    private List<ProvinceDto> provinces;
    private Map<String, List<DistrictDto>> districts;
    private Map<String, List<CommuneDto>> communes;
  }
}