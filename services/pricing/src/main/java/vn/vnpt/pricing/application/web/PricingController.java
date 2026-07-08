package vn.vnpt.pricing.application.web;

import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.pricing.domain.PriceEntry;
import vn.vnpt.pricing.domain.Pricebook;

@RestController
@RequestMapping("/api/pricing")
public class PricingController {

  private final Pricebook pricebook;

  public PricingController(Pricebook pricebook) {
    this.pricebook = pricebook;
  }

  @GetMapping("/{variantId}")
  public ResponseEntity<Map<String, Object>> get(@PathVariable String variantId) {
    Optional<PriceEntry> entry = pricebook.get(variantId);
    if (entry.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    PriceEntry e = entry.get();
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("variantId", variantId);
    body.put("listPriceCents", e.listPriceCents());
    body.put("salePriceCents", e.salePriceCents());
    body.put("currency", e.currency());
    body.put("effectiveAt", e.effectiveAt().toString());
    return ResponseEntity.ok(body);
  }
}