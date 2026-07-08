package vn.vnpt.pricing.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Static pricebook — Story 5.7. Loads {@code pricebook.json} at startup into a Map.
 * Future story replaces this with a real Postgres pricebook table.
 */
@Component
public class Pricebook {

  private static final Logger log = LoggerFactory.getLogger(Pricebook.class);

  private final ObjectMapper objectMapper;
  private final Map<String, PriceEntry> entries = new HashMap<>();

  public Pricebook(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void load() {
    try (InputStream in = new ClassPathResource("pricebook.json").getInputStream()) {
      Seed seed = objectMapper.readValue(in, Seed.class);
      Instant now = Instant.now();
      if (seed.entries != null) {
        for (EntryDto dto : seed.entries) {
          entries.put(dto.variantId, new PriceEntry(
              dto.listPriceCents, dto.salePriceCents, dto.currency, now));
        }
      }
      log.info("Pricebook loaded: {} entries", entries.size());
    } catch (Exception e) {
      log.error("Failed to load pricebook.json", e);
    }
  }

  public Optional<PriceEntry> get(String variantId) {
    return Optional.ofNullable(entries.get(variantId));
  }

  @Data
  static class Seed {
    private List<EntryDto> entries;
  }

  @Data
  static class EntryDto {
    private String variantId;
    private long listPriceCents;
    private Long salePriceCents;
    private String currency;
  }
}