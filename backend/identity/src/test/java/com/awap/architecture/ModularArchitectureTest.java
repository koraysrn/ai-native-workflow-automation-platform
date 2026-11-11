package com.awap.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/** Enforces modular-monolith boundaries that keep services independently deployable. */
@AnalyzeClasses(packages = "com.awap")
class ModularArchitectureTest {

  /**
   * The named classes allowed to keep JPA mapping annotations in the domain layer (issue #199,
   * ADR-0007). The list is the single source of truth for the exception and is validated against
   * the real domain classes by {@link JpaDomainExceptionTest}.
   */
  private static final Set<String> JPA_DOMAIN_EXCEPTION = loadJpaDomainException();

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

  private static Set<String> loadJpaDomainException() {
    try (InputStream input =
        ModularArchitectureTest.class
            .getClassLoader()
            .getResourceAsStream("architecture/jpa-domain-exception.txt")) {
      if (input == null) {
        throw new IllegalStateException(
            "architecture/jpa-domain-exception.txt is missing; issue #199 requires an explicit"
                + " exception inventory");
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        Set<String> names =
            reader
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toUnmodifiableSet());
        if (names.isEmpty()) {
          throw new IllegalStateException(
              "architecture/jpa-domain-exception.txt must name every allowed JPA domain entity");
        }
        return names;
      }
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  static Set<String> jpaDomainException() {
    return JPA_DOMAIN_EXCEPTION;
  }

  /**
   * Issue #199: the JPA mapping annotation exception is narrow. A domain class that is not named in
   * the exception inventory may not import persistence mapping annotations at all.
   */
  @ArchTest
  static final ArchRule jpa_annotations_are_limited_to_the_exception =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .and(
              DescribedPredicate.not(
                  DescribedPredicate.describe(
                      "named in architecture/jpa-domain-exception.txt",
                      javaClass -> JPA_DOMAIN_EXCEPTION.contains(javaClass.getName()))))
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "jakarta.persistence..",
              "org.hibernate.annotations..",
              "org.springframework.data.annotation..")
          .because(
              "ADR-0007 limits the JPA-entity exception to the named classes, and a new entity"
                  + " needs an explicit architecture review");

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
