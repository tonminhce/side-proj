package vn.vnpt.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PricingService stub — Story 5.7 / FR-65, FR-67 placeholder. v1 ships a static pricebook
 * (no DB); a real pricebook table lands with a future story.
 *
 * <p>Pricing has no DB + no JPA + no Modulith outbox. The YML exclusion list (in
 * application.yml) handles the JPA + DataSource + Events autoconfig; the
 * {@code @SpringBootApplication} annotation is clean.
 */
@SpringBootApplication(scanBasePackages = "vn.vnpt.pricing")
public class PricingApplication {

  public static void main(String[] args) {
    SpringApplication.run(PricingApplication.class, args);
  }
}