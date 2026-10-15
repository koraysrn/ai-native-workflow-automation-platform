package com.awap.form.infrastructure;

import com.awap.form.domain.FormDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for form definitions. */
public interface FormDefinitionRepository extends JpaRepository<FormDefinition, UUID> {

  List<FormDefinition> findByTenantIdAndDeletedFalse(UUID tenantId);

  Optional<FormDefinition> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);
}
