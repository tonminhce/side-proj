package vn.vnpt.util.archunit;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Spring Modulith package-boundary enforcement (Story 0.4 / AC #5).
 *
 * <p>Rule: no class may depend on another module's {@code infrastructure/} package. Specifically,
 * no class anywhere in the project may depend on a class that lives in {@code
 * vn.vnpt.<other-module>..infrastructure..}. util's own infrastructure is allow-listed.
 *
 * <p>Companion to Spring Modulith's {@code ApplicationModules.verify()} (which the first
 * multi-module Epic 1 story will introduce via {@code @ApplicationModule}).
 *
 * <p>The rule matches nothing today (util/ is the only populated module), which is the intended
 * Sprint 0 baseline. Once Epic 1 services land, the rule becomes binding.
 */
class ModulithPackageBoundaryTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("vn.vnpt");

  private static DescribedPredicate<JavaClass> foreignInfrastructure() {
    DescribedPredicate<JavaClass> inAnyInfrastructure =
        resideInAPackage("vn.vnpt..infrastructure..");
    DescribedPredicate<JavaClass> inUtil = resideInAPackage("vn.vnpt.util..");
    return inAnyInfrastructure.and(not(inUtil));
  }

  @Test
  void noClassDependsOnForeignInfrastructure() {
    ArchRule rule = noClasses().should().dependOnClassesThat(foreignInfrastructure());

    rule.check(CLASSES);
  }
}
