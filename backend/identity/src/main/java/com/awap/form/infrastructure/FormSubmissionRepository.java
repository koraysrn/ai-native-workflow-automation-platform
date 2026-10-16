package com.awap.form.infrastructure;

import com.awap.form.domain.FormSubmission;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for form submissions. */
public interface FormSubmissionRepository extends JpaRepository<FormSubmission, UUID> {

  List<FormSubmission> findByTenantIdAndFormIdOrderByCreatedAtDesc(UUID tenantId, UUID formId);
}
