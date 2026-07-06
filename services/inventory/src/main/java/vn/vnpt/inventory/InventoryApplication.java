package vn.vnpt.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.modulith.ApplicationModule;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * InventoryService — second runnable Spring Boot module in the platform (after CatalogService from
 * Story 1.1).
 *
 * <p>Scope of this story: bootstrap + per-warehouse append-only ledger (FR-8, ADR-12). The DDL in
 * {@code resources/db/migration/V001__create_inventory_tables.sql} lands the canonical {@code
 * warehouses}, {@code inventory_ledger}, {@code outbox}, and {@code processed_event} tables; the
 * {@code Warehouse} / {@code InventoryLedgerEntry} JPA entities map to those tables in this story.
 *
 * <p>Architectural references:
 *
 * <ul>
 *   <li><b>ADR-01 (Modulith outbox)</b> — Spring Modulith's {@code @ApplicationModule} marks this
 *       class as a logical module boundary. Outbox table per service; CDC propagates read-side
 *       projections (architecture.md line 213).
 *   <li><b>ADR-03 (database-per-service)</b> — InventoryService owns {@code inventory_db}; no
 *       cross-database joins are possible (architecture.md line 212, line 889).
 *   <li><b>ADR-12 (per-warehouse ledger + sum-derivation {@code on_hand})</b> — Story 1.5 ships the
 *       append-only ledger; on_hand is {@code SUM(delta)} per (variant_id, warehouse_id), NEVER a
 *       column. Story 1.6 adds {@code SELECT … FOR UPDATE} for the reservation path
 *       (architecture.md line 223).
 *   <li><b>ADR-14 (per-service outbox)</b> — outbox is local to {@code inventory_db}; the Modulith
 *       bridge publishes to Kafka (architecture.md line 224, line 297).
 * </ul>
 *
 * <p>Quote, epics.md line 260: <i>"Catalog + Inventory services come up together (Sprint 1).
 * Per-warehouse ledger + reservation TTL (FR-9, ADR-12). Outbox table per service. CDC propagates
 * read-side projections."</i>
 *
 * <p>v1 is single-tenant (architecture-detail.md line 76); {@code util}'s {@code
 * TenantInterceptor} / {@code TenantStorage} beans are not exercised here. The {@code
 * @ComponentScan} below is scoped to {@code vn.vnpt.inventory} so util's tenant package is NOT
 * pulled in via Spring's default {@code vn.vnpt} sweep. util's {@code UtilsAutoConfiguration}
 * loads through Spring Boot's autoconfig SPI and is unaffected by this scan.
 *
 * <p>Story 1.5 (ponytail fallback): we exclude the Modulith bridge's {@code
 * JdbcEventPublicationAutoConfiguration} via {@code spring.autoconfigure.exclude} in
 * application.yml so it does NOT manage its own {@code EVENT_PUBLICATION} table. The bridge is on
 * the classpath only for the in-process {@code @ApplicationModuleListener} support (provided by
 * spring-modulith-events-core's {@code EventPublicationAutoConfiguration}). Our {@code
 * ModulithOutboxPublisher} writes the V001 {@code outbox} table directly and fires {@code
 * ApplicationEventPublisher.publishEvent} for the in-process listener (the {@code
 * CatalogEventListener} consumes {@code CatalogProductCreated} from catalog).
 */
/**
 * Story 1.6 enables {@code @EnableScheduling} to activate Spring's {@code @Scheduled} post
 * processor — the {@code ReservationSweeperJob} is the first {@code @Scheduled} job in the
 * codebase. Boot 4's autoconfig detects {@code @Scheduled} on {@code @Component} classes; the
 * {@code @EnableScheduling} annotation is required to activate the post processor.
 */
@SpringBootApplication
@ComponentScan(basePackages = "vn.vnpt.inventory")
@EnableScheduling
@ApplicationModule(displayName = "inventory")
public class InventoryApplication {

  public static void main(String[] args) {
    SpringApplication.run(InventoryApplication.class, args);
  }
}