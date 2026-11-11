package com.awap.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps the issue #199 JPA-domain exception honest: the named list must match the real annotated
 * domain classes, a new annotated class must fail the ArchUnit rule, and the exception must be
 * reviewed before its expiry date.
 */
class JpaDomainExceptionTest {

  /** ADR-0007 expiry. A red build here is the reminder to migrate or renew the exception. */
  private static final LocalDate REVIEW_BY = LocalDate.of(2027, 6, 30);

  private static final JavaClasses ALL_CLASSES = new ClassFileImporter().importPackages("com.awap");

  private static boolean isDomainClass(JavaClass javaClass) {
    String packageName = javaClass.getPackageName();
    return javaClass.getName().startsWith("com.awap.")
        && (packageName.endsWith(".domain") || packageName.contains(".domain."));
  }

  private static boolean hasPersistenceAnnotation(JavaClass javaClass) {
    return javaClass.getDirectDependenciesFromSelf().stream()
        .map(dependency -> dependency.getTargetClass().getName())
        .anyMatch(
            name ->
                name.startsWith("jakarta.persistence.")
                    || name.startsWith("org.hibernate.annotations.")
                    || name.startsWith("org.springframework.data.annotation."));
  }

  private static Set<String> annotatedDomainClasses() {
    return ALL_CLASSES.stream()
        .filter(JpaDomainExceptionTest::isDomainClass)
        .filter(JpaDomainExceptionTest::hasPersistenceAnnotation)
        .map(JavaClass::getName)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Test
  void everyExceptionEntryIsAnExistingAnnotatedDomainClass() {
    for (String name : ModularArchitectureTest.jpaDomainException()) {
      assertThat(ALL_CLASSES.contain(name))
          .as(
              "%s is listed in architecture/jpa-domain-exception.txt but does not exist; remove"
                  + " the stale entry",
              name)
          .isTrue();
      JavaClass javaClass = ALL_CLASSES.get(name);
      assertThat(isDomainClass(javaClass)).as("%s must live in a domain package", name).isTrue();
      assertThat(hasPersistenceAnnotation(javaClass))
          .as("%s no longer uses JPA annotations; drop it from the exception", name)
          .isTrue();
    }
  }

  @Test
  void everyAnnotatedDomainClassIsNamedInTheException() {
    assertThat(annotatedDomainClasses())
        .as(
            "A JPA-annotated domain class outside the named exception must be reviewed; see"
                + " ADR-0007")
        .isEqualTo(ModularArchitectureTest.jpaDomainException());
  }

  @Test
  void aNewAnnotatedDomainClassFailsTheRule() {
    JavaClasses leakyClasses = new ClassFileImporter().importPackages("architecturefixtures");

    assertThatThrownBy(
            () ->
                ModularArchitectureTest.jpa_annotations_are_limited_to_the_exception.check(
                    leakyClasses))
        .hasMessageContaining("LeakyJpaEntity")
        .hasMessageContaining("jakarta.persistence.Entity");
  }

  @Test
  void theExceptionMustBeReviewedBeforeTheExpiryDate() {
    assertThat(LocalDate.now())
        .as(
            "The issue #199 JPA-domain exception expired on %s. Migrate the remaining aggregates or"
                + " renew ADR-0007 and this date.",
            REVIEW_BY)
        .isBefore(REVIEW_BY);
  }
}
