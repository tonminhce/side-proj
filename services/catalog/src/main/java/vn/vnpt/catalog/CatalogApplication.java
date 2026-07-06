package vn.vnpt.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.modulith.ApplicationModule;

/**
 * CatalogService — first runnable Spring Boot module in the platform.
 *
 * <p>Scope of this story: bootstrap only (Story 1.1). The DDL in {@code
 * resources/db/migration/V001__create_catalog_tables.sql} lands the canonical {@code products},
 * {@code variants}, {@code attributes}, {@code outbox}, and {@code processed_event} tables. The
 * {@code Product} / {@code Variant} / {@code Attribute} JPA entities that map to those tables land
 * in Story 1.2.
 *
 * <p>Architectural references:
 *
 * <ul>
 *   <li><b>ADR-01 (Modulith outbox)</b> — Spring Modulith's {@code @ApplicationModule} marks this
 *       class as a logical module boundary. Outbox table per service; CDC propagates read-side
 *       projections (architecture.md line 213).
 *   <li><b>ADR-03 (database-per-service)</b> — CatalogService owns {@code catalog_db}; no
 *       cross-database joins are possible (architecture.md line 212, line 889).
 *   <li><b>ADR-14 (per-service outbox table)</b> — outbox is local to {@code catalog_db}; Modulith
 *       outbox bridge publishes to Kafka (architecture.md line 223, line 297).
 * </ul>
 *
 * <p>Quote, epics.md line 260: <i>"Catalog + Inventory services come up together (Sprint 1).
 * Per-warehouse ledger + reservation TTL (FR-9, ADR-12). Outbox table per service. CDC propagates
 * read-side projections."</i>
 *
 * <p>v1 is single-tenant (architecture-detail.md line 76); {@code util}'s {@code
 * TenantInterceptor} / {@code TenantStorage} beans are not exercised here. The {@code
 * @ComponentScan} below is scoped to {@code vn.vnpt.catalog} so util's tenant package is NOT pulled
 * in via Spring's default {@code vn.vnpt} sweep. util's {@code UtilsAutoConfiguration} loads
 * through Spring Boot's autoconfig SPI ({@code META-INF/spring/...AutoConfiguration.imports}) and
 * is unaffected by this scan.
 *
 * <p>Story 1.3 (ponytail fallback): we exclude the Modulith bridge's
 * {@code JdbcEventPublicationAutoConfiguration} via {@code spring.autoconfigure.exclude} in
 * application.yml so it does NOT manage its own EVENT_PUBLICATION table. The bridge is on the
 * classpath only for the in-process {@code @ApplicationModuleListener} support (provided by
 * spring-modulith-events-core's {@code EventPublicationAutoConfiguration}). Our {@code
 * ModulithOutboxPublisher} writes the V001 {@code outbox} table directly and fires
 * {@code ApplicationEventPublisher.publishEvent} for the in-process listener. Story 1.5+ may
 * re-enable the bridge by removing this exclude and aligning the schema.
 */
@SpringBootApplication
@ComponentScan(basePackages = "vn.vnpt.catalog")
@ApplicationModule(displayName = "catalog")
public class CatalogApplication {

  public static void main(String[] args) {
    SpringApplication.run(CatalogApplication.class, args);
  }
}