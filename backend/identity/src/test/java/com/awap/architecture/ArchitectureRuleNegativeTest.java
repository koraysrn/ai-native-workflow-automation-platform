package com.awap.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * Proves the domain-purity rules actually fail for a leaky class, so the architecture gate cannot
 * silently degrade into a no-op.
 */
class ArchitectureRuleNegativeTest {

  private final JavaClasses leakyClasses =
      new ClassFileImporter().importPackages("architecturefixtures");

  @Test
  void springServiceInDomainFailsTheRule() {
    assertThatThrownBy(
            () -> ModularArchitectureTest.domain_does_not_depend_on_spring.check(leakyClasses))
        .hasMessageContaining("org.springframework.stereotype.Service");
  }

  @Test
  void entityManagerInDomainFailsThePersistenceRule() {
    assertThatThrownBy(
            () ->
                ModularArchitectureTest.domain_does_not_use_persistence_machinery.check(
                    leakyClasses))
        .hasMessageContaining("jakarta.persistence.EntityManager");
  }

  @Test
  void persistenceMachineryPredicateAllowsMappingAnnotations() {
    JavaClasses classes = new ClassFileImporter().importPackages("com.awap.identity.domain");
    // The real domain entities use mapping annotations (jakarta.persistence.Entity, etc.) and must
    // remain valid; the rule only rejects machinery, never mapping annotations.
    ModularArchitectureTest.domain_does_not_use_persistence_machinery.check(classes);
  }
}
