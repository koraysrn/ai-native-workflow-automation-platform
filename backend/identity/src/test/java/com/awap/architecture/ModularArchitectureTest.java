package com.awap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces modular-monolith boundaries that keep services independently deployable. */
@AnalyzeClasses(packages = "com.awap")
class ModularArchitectureTest {

  @ArchTest
  static final ArchRule domain_does_not_depend_on_spring =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat(
              DescribedPredicate.describe(
                  "reside in org.springframework.. (excluding data annotations)",
                  javaClass ->
                      javaClass.getName().startsWith("org.springframework.")
                          && !javaClass
                              .getName()
                              .startsWith("org.springframework.data.annotation.")));

  @ArchTest
  static final ArchRule modules_do_not_access_other_infrastructure =
      noClasses()
          .that()
          .resideInAPackage("com.awap.engine..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.awap.workflow.infrastructure..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("com.awap.task.infrastructure..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("com.awap.form.infrastructure..")
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("com.awap.organization.infrastructure..");
}
