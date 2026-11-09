package com.awap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

/** Enforces modular-monolith boundaries that keep services independently deployable. */
@AnalyzeClasses(packages = "com.awap")
class ModularArchitectureTest {

  private static final Set<String> JPA_MACHINERY =
      Set.of(
          "jakarta.persistence.EntityManager",
          "jakarta.persistence.EntityManagerFactory",
          "jakarta.persistence.EntityTransaction",
          "jakarta.persistence.Persistence",
          "jakarta.persistence.Query",
          "jakarta.persistence.TypedQuery",
          "jakarta.persistence.StoredProcedureQuery",
          "jakarta.persistence.Parameter",
          "jakarta.persistence.LockModeType",
          "jakarta.persistence.FlushModeType",
          "jakarta.persistence.metamodel.Metamodel");

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

  /**
   * ADR-001 chooses package-level layers, so the outer layers of every module must never be visible
   * from a domain package. Domain-to-domain dependencies across modules remain allowed.
   */
  @ArchTest
  static final ArchRule domain_does_not_depend_on_outer_layers =
      noClasses()
          .that()
          .resideInAPackage("com.awap.(*).domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.awap.(*).application..",
              "com.awap.(*).infrastructure..",
              "com.awap.(*).interfaces..");

  /**
   * The domain may use mapping annotations but must not contain persistence logic: no entity
   * manager, no criteria queries, no repositories and no Hibernate APIs beyond mapping annotations.
   */
  @ArchTest
  static final ArchRule domain_does_not_use_persistence_machinery =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat(
              DescribedPredicate.describe(
                  "persistence machinery", ModularArchitectureTest::isPersistenceMachinery));

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

  static boolean isPersistenceMachinery(JavaClass javaClass) {
    String name = javaClass.getName();
    return JPA_MACHINERY.contains(name)
        || name.startsWith("jakarta.persistence.criteria.")
        || name.startsWith("org.springframework.data.jpa.")
        || name.startsWith("org.springframework.data.repository.")
        || (name.startsWith("org.hibernate.") && !name.startsWith("org.hibernate.annotations."));
  }
}
