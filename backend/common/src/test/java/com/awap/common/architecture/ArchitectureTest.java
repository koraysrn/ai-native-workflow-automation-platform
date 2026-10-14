package com.awap.common.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the framework independence of the domain layer. The rule is empty until domain packages
 * are introduced, but is wired here as the canonical boundary guard for all AWAP modules.
 */
@AnalyzeClasses(packages = "com.awap.common")
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainMustNotDependOnSpring =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..")
          .allowEmptyShould(true);
}
