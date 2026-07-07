package vn.vnpt.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.modulith.ApplicationModule;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CartService — anonymous cart + idempotent merge on login (Story 2.1 / FR-14, FR-15, FR-16).
 *
 * <p>Architectural references:
 *
 * <ul>
 *   <li><b>ADR-01 (Modulith module)</b> — {@code @ApplicationModule} marks this class as a logical
 *       module boundary; cross-service comms are Modulith events, never Java imports.
 *   <li><b>ADR-03 (database-per-service)</b> — CartService owns {@code cart_db}; no cross-database
 *       joins with {@code catalog_db} / {@code inventory_db}. {@code CartLine.variantId} is a
 *       cross-service reference with NO FK.
 *   <li><b>ADR-14 (per-service outbox)</b> — the {@code outbox} table is local to {@code cart_db};
 *       {@code ModulithOutboxPublisher} writes it directly and fires in-process listeners.
 *   <li><b>ADR-07 (B2C v1)</b> — single-tenant default ({@code tenant_id = 'default'}); marketplace
 *       {@code sellerId} on {@code CartLine} is nullable (populated in v2).
 * </ul>
 *
 * <p>{@code @EnableScheduling} mirrors the inventory shape (no {@code @Scheduled} jobs ship in Story
 * 2.1; the cart auto-expire sweeper lands in Story 2.2). The {@code @ComponentScan} is scoped to
 * {@code vn.vnpt.cart} so util's tenant package is not pulled in via Spring's default {@code
 * vn.vnpt} sweep; util's {@code UtilsAutoConfiguration} loads through the autoconfig SPI.
 */
@SpringBootApplication
@ComponentScan(basePackages = "vn.vnpt.cart")
@EnableScheduling
@ApplicationModule(displayName = "cart")
public class CartApplication {

  public static void main(String[] args) {
    SpringApplication.run(CartApplication.class, args);
  }
}
