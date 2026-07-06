package vn.vnpt.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;

/**
 * Modulith package-boundary enforcement for InventoryService (Story 1.5 / AC #23).
 *
 * <p>Rules:
 *
 * <ol>
 *   <li>{@link #inventory_doesNotDependOnSiblingServices()} — no class under {@code
 *       vn.vnpt.inventory..} may depend on a sibling service's package. InventoryService
 *       communicates with siblings via Modulith events (ADR-01, ADR-04) — Java imports are
 *       forbidden (ADR-03).
 *       <p>Allow-list: {@code vn.vnpt.catalog.domain.event..} (events are cross-service contracts
 *       per ADR-04; entities are not). Implemented via a custom predicate that excludes
 *       {@code catalog.domain.event..} from the {@code catalog.domain..} forbidden set.
 *   <li>{@link #inventory_writesOnlyToInventoryLedger()} — append-only invariant on
 *       {@link InventoryLedgerEntryRepository}. No {@code void delete*(...)} method may be
 *       declared. Enforcement: the test scans the repository for declared methods and fails on
 *       any {@code delete*} method.
 *       <p>ponytail: app-level enforcement. A Postgres {@code BEFORE UPDATE OR DELETE} trigger
 *       on {@code inventory_ledger} is the canonical defense; YAGNI for v1. A hardening story
 *       (10.4) adds the trigger.
 * </ol>
 */
class InventoryPackageBoundaryTest {

  /**
   * Matches classes in {@code vn.vnpt.catalog.domain..} EXCEPT those in
   * {@code vn.vnpt.catalog.domain.event..} (events are cross-service contracts).
   *
   * <p>Note: no trailing dot on the event exclusion — {@code JavaClass.getPackageName()} returns
   * the bare package (e.g. {@code "vn.vnpt.catalog.domain.event"}), not a wildcard pattern.
   */
  private static final DescribedPredicate<JavaClass> CATALOG_DOMAIN_BUT_NOT_EVENTS =
      new DescribedPredicate<>("catalog domain classes but not events") {
        @Override
        public boolean test(JavaClass javaClass) {
          String pkg = javaClass.getPackageName();
          return pkg.startsWith("vn.vnpt.catalog.domain.")
              && !pkg.startsWith("vn.vnpt.catalog.domain.event");
        }
      };

  @Test
  void inventory_doesNotDependOnSiblingServices() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.inventory..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            // Sibling services (forbidden — ADR-03 cross-DB).
            "vn.vnpt.cart..",
            "vn.vnpt.checkout..",
            "vn.vnpt.payment..",
            "vn.vnpt.order..",
            "vn.vnpt.fulfillment..",
            "vn.vnpt.returns..",
            "vn.vnpt.customer..",
            "vn.vnpt.search..",
            "vn.vnpt.notification..",
            "vn.vnpt.admin..",
            "vn.vnpt.pricing..",
            "vn.vnpt.invoice..")
        .orShould()
        .dependOnClassesThat(CATALOG_DOMAIN_BUT_NOT_EVENTS)
        .because(
            "InventoryService communicates with siblings via Modulith events (ADR-01, ADR-04),"
                + " NOT Java imports (ADR-03). Only vn.vnpt.catalog.domain.event.. is allowed"
                + " (events are cross-service contracts).")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.inventory"));
  }

  /**
   * Append-only enforcement: NO {@code void delete*(...)} method may be declared on
   * {@link InventoryLedgerEntryRepository}. The test uses reflection to enumerate declared
   * methods (NOT inherited JpaRepository methods — the rule is on app code, not the framework
   * interface).
   */
  @Test
  void inventory_writesOnlyToInventoryLedger() {
    // ponytail: app-level enforcement; DB trigger is YAGNI v1.
    Method[] methods = InventoryLedgerEntryRepository.class.getDeclaredMethods();
    for (Method m : methods) {
      if (m.getName().startsWith("delete")) {
        throw new AssertionError(
            "InventoryLedgerEntryRepository must be append-only; found: " + m);
      }
    }
  }
}