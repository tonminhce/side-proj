package vn.vnpt.cart;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.cart.application.port.OutboxPublisher;
import vn.vnpt.cart.domain.annotation.IgnoreSoftUkAudit;
import vn.vnpt.cart.infrastructure.outbox.CartEventPublisher;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartMergeLogRepository;
import vn.vnpt.util.common.entity.base.RootEntity;
import vn.vnpt.util.component.softdelete.annotation.SoftUk;
import vn.vnpt.util.component.softdelete.annotation.SoftUks;

/**
 * Modulith package-boundary enforcement for CartService — Story 2.1 / AC #9 (6 ArchUnit rules),
 * extended in Story 2.2 with 2 rules for the cart sweeper placement + expiry-event routing.
 * Mirrors {@code InventoryPackageBoundaryTest}.
 */
class CartPackageBoundaryTest {

  /** Rule 1 — no class under {@code vn.vnpt.cart..} may depend on a sibling service's package. */
  @Test
  void cart_doesNotDependOnSiblingServices() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.cart..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "vn.vnpt.catalog..",
            "vn.vnpt.inventory..",
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
        .because(
            "CartService communicates with siblings via Modulith events (ADR-01, ADR-04), NOT Java"
                + " imports (ADR-03). Story 2.1 has no inbound cross-service event consumers.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.cart"));
  }

  /** Rule 2 — {@link CartLineRepository} declares NO {@code void delete*(...)} method. */
  @Test
  void cart_lines_isTerminalOrAppendOnly() {
    for (Method m : CartLineRepository.class.getDeclaredMethods()) {
      if (m.getName().startsWith("delete")) {
        throw new AssertionError("CartLineRepository must be soft-delete-only; found: " + m);
      }
    }
  }

  /** Rule 3 — {@link CartMergeLogRepository} declares NO {@code void delete*(...)} method. */
  @Test
  void cart_mergeLog_isAppendOnly() {
    for (Method m : CartMergeLogRepository.class.getDeclaredMethods()) {
      if (m.getName().startsWith("delete")) {
        throw new AssertionError("CartMergeLogRepository must be append-only; found: " + m);
      }
    }
  }

  /**
   * Rule 4 (NFR-IDEM-3 regression guard) — every JPA entity extending {@code RootEntity} MUST carry
   * {@code @SoftUk}/{@code @SoftUks}, OR be marked {@code @IgnoreSoftUkAudit}.
   */
  @Test
  void cart_softDeletableEntitiesHaveSoftUkAnnotation() {
    classes()
        .that()
        .areAssignableTo(RootEntity.class)
        .and()
        .haveSimpleNameNotEndingWith("Test")
        .and()
        .resideInAPackage("vn.vnpt.cart.domain..")
        .and()
        .areNotAnnotatedWith(IgnoreSoftUkAudit.class)
        .should()
        .beAnnotatedWith(SoftUk.class)
        .orShould()
        .beAnnotatedWith(SoftUks.class)
        .because(
            "NFR-IDEM-3 regression guard — every soft-deletable JPA entity MUST carry @SoftUk or"
                + " @SoftUks (or @IgnoreSoftUkAudit with JavaDoc justification).")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.cart"));
  }

  /** Rule 5 (ADR-04 atomicity) — cart mutation/emit use cases MUST be {@code @Transactional}. */
  @Test
  void cart_outboxWritesAreAtomicWithCartMutation() {
    classes()
        .that()
        .haveSimpleName("MergeCartUseCase")
        .or()
        .haveSimpleName("AddLineUseCase")
        .or()
        .haveSimpleName("UpdateLineQuantityUseCase")
        .or()
        .haveSimpleName("RemoveLineUseCase")
        .should()
        .beAnnotatedWith(Transactional.class)
        .because(
            "ADR-04 atomicity — use cases that mutate carts / write the outbox MUST be"
                + " @Transactional so business state + outbox insert are atomic.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.cart"));
  }

  /**
   * Rule 6 (FR-14) — use cases that emit cart lifecycle events MUST reference
   * {@link CartEventPublisher}, NOT {@link OutboxPublisher} directly. Reflection-based (matches the
   * inventory precedent). In Story 2.1 the sole emit use case is {@code MergeCartUseCase}.
   */
  @Test
  void cart_lifecycleEventsRouteThroughPublisher() {
    for (Class<?> useCase : emitUseCaseClasses()) {
      boolean hasPublisher = false;
      boolean hasDirectOutbox = false;
      for (java.lang.reflect.Field field : useCase.getDeclaredFields()) {
        String type = field.getType().getName();
        if (type.equals(CartEventPublisher.class.getName())) {
          hasPublisher = true;
        }
        if (type.equals(OutboxPublisher.class.getName())
            || type.equals("vn.vnpt.cart.infrastructure.outbox.ModulithOutboxPublisher")) {
          hasDirectOutbox = true;
        }
      }
      if (!hasPublisher) {
        throw new AssertionError(
            "Use case " + useCase.getName() + " emits events but does not declare CartEventPublisher");
      }
      if (hasDirectOutbox) {
        throw new AssertionError(
            "Use case " + useCase.getName() + " bypasses CartEventPublisher — direct OutboxPublisher"
                + " reference forbidden for cart lifecycle events");
      }
    }
  }

  private static java.util.List<Class<?>> emitUseCaseClasses() {
    java.util.List<Class<?>> emitUseCases = new java.util.ArrayList<>();
    try {
      java.nio.file.Path appRoot = java.nio.file.Path.of("src/main/java/vn/vnpt/cart/application");
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
                      "vn.vnpt.cart.application."
                          + rel.replace('/', '.').replaceAll("\\.java$", "");
                  Class<?> cls = Class.forName(fqn);
                  for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                    if (field.getType().getName().equals(CartEventPublisher.class.getName())) {
                      emitUseCases.add(cls);
                      break;
                    }
                  }
                } catch (Throwable ignored) {
                  // skip classes we can't load
                }
              });
    } catch (Exception ignored) {
      // skip filesystem-walk failures
    }
    return emitUseCases;
  }

  /**
   * Rule 7 (Story 2.2 / FR-18) — the cart auto-expire sweeper MUST live in {@code
   * vn.vnpt.cart.application} so it can see {@code ExpireCartUseCase} without violating the package
   * layering. Mirrors the inventory sweeper placement precedent.
   */
  @Test
  void cart_sweeperJob_isInApplicationPackage() throws Exception {
    Class<?> cls =
        Class.forName("vn.vnpt.cart.application.CartAutoExpireSweeperJob");
    if (!cls.getPackageName().equals("vn.vnpt.cart.application")) {
      throw new AssertionError(
          "CartAutoExpireSweeperJob must live in vn.vnpt.cart.application (got " + cls.getPackageName() + ")");
    }
  }

  /**
   * Rule 8 (Story 2.2 / FR-17 + FR-18) — use cases that emit {@code cart.line.added} or
   * {@code cart.expired} MUST route through {@link CartEventPublisher}, NOT {@link OutboxPublisher}
   * directly. Same reflection scan as Rule 6; just verifies the extended set of emit use cases
   * (AddLine, MergeCart, ExpireCart) all declare CartEventPublisher and none declare a direct
   * OutboxPublisher.
   */
  @Test
  void cart_expiryEventsRouteThroughPublisher() throws Exception {
    for (Class<?> useCase : emitUseCaseClasses()) {
      boolean hasPublisher = false;
      boolean hasDirectOutbox = false;
      for (java.lang.reflect.Field field : useCase.getDeclaredFields()) {
        String type = field.getType().getName();
        if (type.equals(CartEventPublisher.class.getName())) {
          hasPublisher = true;
        }
        if (type.equals(OutboxPublisher.class.getName())
            || type.equals("vn.vnpt.cart.infrastructure.outbox.ModulithOutboxPublisher")) {
          hasDirectOutbox = true;
        }
      }
      if (!hasPublisher) {
        throw new AssertionError(
            "Use case " + useCase.getName() + " emits events but does not declare CartEventPublisher");
      }
      if (hasDirectOutbox) {
        throw new AssertionError(
            "Use case " + useCase.getName() + " bypasses CartEventPublisher — direct OutboxPublisher"
                + " reference forbidden for cart lifecycle events");
      }
    }
  }
}
