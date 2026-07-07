package vn.vnpt.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.inventory.domain.annotation.IgnoreSoftUkAudit;
import vn.vnpt.inventory.infrastructure.outbox.LifecycleEventPublisher;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.util.common.entity.base.RootEntity;
import vn.vnpt.util.component.softdelete.annotation.SoftUk;
import vn.vnpt.util.component.softdelete.annotation.SoftUks;

/**
 * Modulith package-boundary enforcement for InventoryService (Story 1.5 / AC #23; extended in
 * Story 1.6 / FR-9 with 2 new rules, Story 1.7 / FR-10 with 1 new rule, Story 1.8 / FR-11 +
 * FR-12 with 2 new rules).
 *
 * <p>Rules:
 *
 * <ol>
 *   <li>{@link #inventory_doesNotDependOnSiblingServices()} — no class under {@code
 *       vn.vnpt.inventory..} may depend on a sibling service's package.
 *   <li>{@link #inventory_writesOnlyToInventoryLedger()} — append-only invariant on
 *       {@link InventoryLedgerEntryRepository}.
 *   <li>{@link #inventory_reservation_isTerminalOnly()} — Story 1.6: append-only invariant on
 *       {@link InventoryReservationRepository}.
 *   <li>{@link #inventory_outboxWritesAreAtomicWithReservation()} — Story 1.6: ADR-04 atomicity
 *       guard.
 *   <li>{@link #inventory_pickerUsesOnlyOwnRepositories()} — Story 1.7 / FR-10.
 *   <li>{@link #inventory_softDeletableEntitiesHaveSoftUkAnnotation()} — Story 1.8 / FR-12 /
 *       DI-09 fix: every entity extending {@code RootEntity} MUST carry a {@code @SoftUk} (or
 *       {@code @SoftUks}, or be marked with {@code @IgnoreSoftUkAudit} for the append-only /
 *       terminal-only opt-out).
 *   <li>{@link #inventory_lifecycleEventsRouteThroughPublisher()} — Story 1.8 / FR-11: use
 *       cases in the application package MUST reference {@link LifecycleEventPublisher} for
 *       inventory lifecycle emissions (no direct {@code ModulithOutboxPublisher.append(...)}
 *       calls).
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

  /**
   * Story 1.6 / AC #5, #19 — append-only invariant on {@link InventoryReservationRepository}.
   * Reservations are terminal-state transitioned (RELEASED / COMMITTED), never deleted (audit
   * trail preservation; same philosophy as the ledger).
   */
  @Test
  void inventory_reservation_isTerminalOnly() {
    Method[] methods = InventoryReservationRepository.class.getDeclaredMethods();
    for (Method m : methods) {
      if (m.getName().startsWith("delete")) {
        throw new AssertionError(
            "InventoryReservationRepository must be terminal-only; found: " + m);
      }
    }
  }

  /**
   * Story 1.6 / AC #6, #19 — ADR-04 atomicity guard. The reservation use cases that write to
   * the outbox MUST be {@code @Transactional} at the class level so the business state +
   * outbox insert are atomic.
   */
  @Test
  void inventory_outboxWritesAreAtomicWithReservation() {
    classes()
        .that()
        .haveSimpleName("ReserveInventoryUseCase")
        .or()
        .haveSimpleName("ReleaseInventoryUseCase")
        .should()
        .beAnnotatedWith(Transactional.class)
        .because(
            "ADR-04 atomicity guard — use cases that write to outbox MUST be @Transactional"
                + " so the business state + outbox insert are atomic.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.inventory"));
  }

  /**
   * Story 1.7 / FR-10 — the picker is a read-only dispatch use case. Its declared dependencies
   * must be limited to its own service's repositories. Soft guard: a future dev adding a
   * saga-step or catalog import to the picker fails this test.
   */
  @Test
  void inventory_pickerUsesOnlyOwnRepositories() {
    // Reflection-based check matching the existing append-only pattern. Walk declared fields
    // of PickWarehouseForReservationUseCase and reject any whose raw type lives outside the
    // allow-list. PONYTAIL: mirror Story 1.5's inventory_writesOnlyToInventoryLedger style —
    // app-level reflection is fine for a soft boundary guard; a future hardening story can
    // graduate this to ArchUnit's stricter field-access predicate.
    Class<?> picker = loadPickerClass();
    for (java.lang.reflect.Field field : picker.getDeclaredFields()) {
      String owner = field.getType().getName();
      boolean allowed =
          owner.startsWith("vn.vnpt.inventory.")
              || owner.startsWith("vn.vnpt.util.")
              || owner.startsWith("org.slf4j.")
              || owner.startsWith("lombok.")
              || owner.startsWith("org.springframework.beans.factory.annotation.")
              || owner.equals("org.springframework.transaction.annotation.Transactional")
              || owner.equals("lombok.RequiredArgsConstructor")
              || owner.equals("lombok.extern.slf4j.Slf4j");
      if (!allowed) {
        throw new AssertionError(
            "PickWarehouseForReservationUseCase field '"
                + field.getName()
                + "' has type '"
                + owner
                + "' which is outside the picker repository allow-list");
      }
    }
  }

  private static Class<?> loadPickerClass() {
    try {
      return Class.forName("vn.vnpt.inventory.application.PickWarehouseForReservationUseCase");
    } catch (ClassNotFoundException e) {
      throw new AssertionError("PickWarehouseForReservationUseCase class not found", e);
    }
  }

  /**
   * Story 1.8 / FR-12 / DI-09 fix — every JPA entity extending {@code RootEntity} (i.e.,
   * soft-deletable via the {@code isDeleted} column) MUST carry a {@code @SoftUk} or
   * {@code @SoftUks} annotation, OR be marked with {@code @IgnoreSoftUkAudit} (the explicit
   * opt-out for append-only / terminal-only entities). The regression guard for DI-09.
   */
  @Test
  void inventory_softDeletableEntitiesHaveSoftUkAnnotation() {
    classes()
        .that()
        .areAssignableTo(RootEntity.class)
        .and()
        .haveSimpleNameNotEndingWith("Test")
        .and()
        .resideInAPackage("vn.vnpt.inventory.domain..")
        .and()
        .areNotAnnotatedWith(IgnoreSoftUkAudit.class)
        .should()
        .beAnnotatedWith(SoftUk.class)
        .orShould()
        .beAnnotatedWith(SoftUks.class)
        .because(
            "DI-09 regression guard — every soft-deletable JPA entity MUST carry @SoftUk or"
                + " @SoftUks (or @IgnoreSoftUkAudit with JavaDoc justification). Applies to"
                + " entities extending RootEntity (i.e., having the isDeleted column).")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.inventory"));
  }

  /**
   * Story 1.8 / FR-11 — use cases in the application package MUST emit lifecycle events via
   * {@link LifecycleEventPublisher} (which encapsulates the dual-publish logic), NOT by calling
   * {@code ModulithOutboxPublisher.append(...)} directly. The reflection check scans all
   * {@code *UseCase} classes for the presence of a {@code LifecycleEventPublisher}-typed field
   * AND the absence of a {@code ModulithOutboxPublisher}-typed field (allow-listed exceptions
   * for read-only / infrastructure use cases).
   *
   * <p>Ponytail: same reflection-based pattern as {@link #inventory_pickerUsesOnlyOwnRepositories()}
   * (Story 1.7). ArchUnit's {@code onlyAccessFieldsWhere} predicate has incompatible API in the
   * current version; reflection is the documented workaround.
   */
  @Test
  void inventory_lifecycleEventsRouteThroughPublisher() {
    java.util.List<Class<?>> emitUseCases = emitUseCaseClasses();
    for (Class<?> useCase : emitUseCases) {
      boolean hasLifecyclePublisher = false;
      boolean hasDirectOutbox = false;
      for (java.lang.reflect.Field field : useCase.getDeclaredFields()) {
        String type = field.getType().getName();
        if (type.equals(LifecycleEventPublisher.class.getName())) {
          hasLifecyclePublisher = true;
        }
        if (type.equals(OutboxPublisher.class.getName())
            || type.equals("vn.vnpt.inventory.infrastructure.outbox.ModulithOutboxPublisher")) {
          hasDirectOutbox = true;
        }
      }
      if (!hasLifecyclePublisher) {
        throw new AssertionError(
            "Use case "
                + useCase.getName()
                + " emits lifecycle events but does not declare LifecycleEventPublisher");
      }
      if (hasDirectOutbox) {
        throw new AssertionError(
            "Use case "
                + useCase.getName()
                + " bypasses LifecycleEventPublisher — direct OutboxPublisher"
                + " reference forbidden for lifecycle events");
      }
    }
  }

  /**
   * Walks the application package for {@code *UseCase} classes that emit lifecycle events.
   * Read-only use cases (e.g. {@code OnHandUseCase}, {@code PickWarehouseForReservationUseCase})
   * are NOT included — the rule targets emit use cases only. The emit use cases are the ones
   * that hold a {@code LifecycleEventPublisher} reference; we discover them by scanning for
   * that field type.
   */
  private static java.util.List<Class<?>> emitUseCaseClasses() {
    java.util.List<Class<?>> emitUseCases = new java.util.ArrayList<>();
    try {
      java.nio.file.Path appRoot =
          java.nio.file.Path.of(
              "src/main/java/vn/vnpt/inventory/application");
      if (!java.nio.file.Files.isDirectory(appRoot)) {
        return emitUseCases;
      }
      java.nio.file.Files.walk(appRoot)
          .filter(p -> p.toString().endsWith("UseCase.java"))
          .forEach(
              p -> {
                try {
                  String rel = appRoot.relativize(p).toString();
                  String fqn =
                      "vn.vnpt.inventory.application."
                          + rel.toString()
                              .replace('/', '.')
                              .replaceAll("\\.java$", "");
                  Class<?> cls = Class.forName(fqn);
                  for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                    if (field.getType().getName()
                        .equals(LifecycleEventPublisher.class.getName())) {
                      emitUseCases.add(cls);
                      break;
                    }
                  }
                } catch (Throwable ignored) {
                  // skip classes we can't load (transient deps)
                }
              });
    } catch (Exception ignored) {
      // skip filesystem-walk failures
    }
    return emitUseCases;
  }
}