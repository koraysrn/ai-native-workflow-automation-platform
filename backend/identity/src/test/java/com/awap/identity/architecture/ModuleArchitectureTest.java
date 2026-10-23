package com.awap.identity.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the layering boundaries of the identity module: the domain layer must not depend on the
 * application, infrastructure or interfaces layers, nor on Spring's DI/web/transaction machinery.
 */
@AnalyzeClasses(packages = "com.awap")
class ModuleArchitectureTest {

  @ArchTest
  static final ArchRule domainMustNotDependOnOuterLayers =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..application..", "..infrastructure..", "..interfaces..");

  @ArchTest
  static final ArchRule domainMustNotDependOnSpringFrameworks =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.stereotype..",
              "org.springframework.transaction..",
              "org.springframework.web..",
              "org.springframework.context..",
              "org.springframework.security..");
}
