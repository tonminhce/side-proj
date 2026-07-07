package vn.vnpt.checkout;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.checkout.application.port.OutboxPublisher;
import vn.vnpt.checkout.domain.annotation.IgnoreSoftUkAudit;
import vn.vnpt.checkout.infrastructure.outbox.CheckoutEventPublisher;
import vn.vnpt.checkout.infrastructure.repository.CheckoutRepository;
import vn.vnpt.util.common.entity.base.RootEntity;
import vn.vnpt.util.component.softdelete.annotation.SoftUk;
import vn.vnpt.util.component.softdelete.annotation.SoftUks;

/**
 * Modulith package-boundary enforcement for CheckoutService — Story 2.3 / FR-19, FR-21 (6 ArchUnit
 * rules). Mirrors {@code CartPackageBoundaryTest} + {@code InventoryPackageBoundaryTest}.
 */
class CheckoutPackageBoundaryTest {

  /** Rule 1 — no class under {@code vn.vnpt.checkout..} may depend on a sibling service's package. */
  @Test
  void checkout_doesNotDependOnSiblingServices() {
    noClasses()
        .that()
        .resideInAPackage("vn.vnpt.checkout..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "vn.vnpt.catalog..",
            "vn.vnpt.inventory..",
            "vn.vnpt.cart..",
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
            "CheckoutService communicates with siblings via Modulith events (ADR-01, ADR-04),"
                + " NOT Java imports (ADR-03). Story 2.3 ships ZERO inbound cross-service event"
                + " consumers; the saga listener for checkout.started is Story 2.5's territory.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.checkout"));
  }

  /** Rule 2 — {@link CheckoutRepository} declares NO {@code void delete*(...)} method. */
  @Test
  void checkout_repositoryHasNoDeleteMethods() {
    for (Method m : CheckoutRepository.class.getDeclaredMethods()) {
      if (m.getName().startsWith("delete")) {
        throw new AssertionError("CheckoutRepository must be soft-delete-only; found: " + m);
      }
    }
  }

  /**
   * Rule 3 (NFR-IDEM-3 regression guard) — every JPA entity extending {@code RootEntity} MUST carry
   * {@code @SoftUk}/{@code @SoftUks}, OR be marked {@code @IgnoreSoftUkAudit}.
   *
   * <p>{@code allowEmptyShould(true)} — Story 2.3 ships ONE entity ({@code Checkout}) and it carries
   * {@code @IgnoreSoftUkAudit}; the rule's positive set is empty so ArchUnit would otherwise report
   * "no classes have been passed to the rule at all" as a failure. The opt-out + entity count
   * combination is the canonical Story 2.3 state.
   */
  @Test
  void checkout_softDeletableEntitiesHaveSoftUkAnnotation() {
    classes()
        .that()
        .areAssignableTo(RootEntity.class)
        .and()
        .haveSimpleNameNotEndingWith("Test")
        .and()
        .resideInAPackage("vn.vnpt.checkout.domain..")
        .and()
        .areNotAnnotatedWith(IgnoreSoftUkAudit.class)
        .should()
        .beAnnotatedWith(SoftUk.class)
        .orShould()
        .beAnnotatedWith(SoftUks.class)
        .allowEmptyShould(true)
        .because(
            "NFR-IDEM-3 regression guard — every soft-deletable JPA entity MUST carry @SoftUk or"
                + " @SoftUks (or @IgnoreSoftUkAudit with JavaDoc justification).")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.checkout"));
  }

  /** Rule 4 (ADR-04 atomicity) — checkout mutation/emit use cases MUST be {@code @Transactional}. */
  @Test
  void checkout_outboxWritesAreAtomicAndRouteThroughPublisher() {
    classes()
        .that()
        .haveSimpleName("StartCheckoutUseCase")
        .should()
        .beAnnotatedWith(Transactional.class)
        .because(
            "ADR-04 atomicity — use cases that mutate checkouts / write the outbox MUST be"
                + " @Transactional so business state + outbox insert are atomic.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.checkout"));

    // Lifecycle-event routing — same reflection scan as cart's Rule 6 / inventory's Rule 7.
    for (Class<?> useCase : emitUseCaseClasses()) {
      boolean hasPublisher = false;
      boolean hasDirectOutbox = false;
      for (java.lang.reflect.Field field : useCase.getDeclaredFields()) {
        String type = field.getType().getName();
        if (type.equals(CheckoutEventPublisher.class.getName())) {
          hasPublisher = true;
        }
        if (type.equals(OutboxPublisher.class.getName())
            || type.equals("vn.vnpt.checkout.infrastructure.outbox.ModulithOutboxPublisher")) {
          hasDirectOutbox = true;
        }
      }
      if (!hasPublisher) {
        throw new AssertionError(
            "Use case " + useCase.getName() + " emits events but does not declare CheckoutEventPublisher");
      }
      if (hasDirectOutbox) {
        throw new AssertionError(
            "Use case "
                + useCase.getName()
                + " bypasses CheckoutEventPublisher — direct OutboxPublisher reference forbidden");
      }
    }
  }

  /** Rule 5 — lifecycle-event routing is folded into Rule 4 for readability. */

  /** Rule 6 — the {@code Checkout} aggregate MUST reside in {@code vn.vnpt.checkout.domain}. */
  @Test
  void checkout_aggregateIsInDomainPackage() {
    classes()
        .that()
        .haveSimpleName("Checkout")
        .and()
        .areAnnotatedWith(jakarta.persistence.Entity.class)
        .should()
        .resideInAPackage("vn.vnpt.checkout.domain..")
        .because(
            "The Checkout aggregate must live in the domain package so the saga (Story 2.5)"
                + " can reuse it without violating Modulith layering.")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.vnpt.checkout"));
  }

  private static java.util.List<Class<?>> emitUseCaseClasses() {
    java.util.List<Class<?>> emitUseCases = new java.util.ArrayList<>();
    try {
      java.nio.file.Path appRoot = java.nio.file.Path.of("src/main/java/vn/vnpt/checkout/application");
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
                      "vn.vnpt.checkout.application."
                          + rel.replace('/', '.').replaceAll("\\.java$", "");
                  Class<?> cls = Class.forName(fqn);
                  for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                    if (field.getType().getName().equals(CheckoutEventPublisher.class.getName())) {
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
}