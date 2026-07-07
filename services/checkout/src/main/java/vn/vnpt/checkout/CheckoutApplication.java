package vn.vnpt.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.modulith.ApplicationModule;

/**
 * CheckoutService — single-page checkout API (Story 2.3 / FR-19, FR-21).
 *
 * <p>Architectural references:
 *
 * <ul>
 *   <li><b>ADR-01 (Modulith module)</b> — {@code @ApplicationModule} marks this class as a logical
 *       module boundary; cross-service comms are Modulith events, never Java imports.
 *   <li><b>ADR-03 (database-per-service)</b> — CheckoutService owns {@code checkout_db}; no
 *       cross-database joins with {@code cart_db}. {@code Checkout.cartUuid} is a cross-service
 *       reference with NO FK.
 *   <li><b>ADR-14 (per-service outbox)</b> — the {@code outbox} table is local to
 *       {@code checkout_db}; {@code ModulithOutboxPublisher} writes it directly and fires
 *       in-process listeners.
 *   <li><b>ADR-07 (B2C v1)</b> — single-tenant default ({@code tenant_id = 'default'}); marketplace
 *       {@code sellerId} on {@code CartLineSnapshot} is nullable (populated in v2).
 * </ul>
 *
 * <p>No {@code @EnableScheduling} in Story 2.3 — the saga sweeper is Story 2.5. The
 * {@code @ComponentScan} is scoped to {@code vn.vnpt.checkout} so util's tenant package is not
 * pulled in via Spring's default {@code vn.vnpt} sweep; util's {@code UtilsAutoConfiguration} loads
 * through the autoconfig SPI.
 */
@SpringBootApplication
@ComponentScan(basePackages = "vn.vnpt.checkout")
@ApplicationModule(displayName = "checkout")
public class CheckoutApplication {

  public static void main(String[] args) {
    SpringApplication.run(CheckoutApplication.class, args);
  }
}