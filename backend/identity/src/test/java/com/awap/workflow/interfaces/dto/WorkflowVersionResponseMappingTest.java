package com.awap.workflow.interfaces.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.workflow.domain.WorkflowStatus;
import com.awap.workflow.domain.WorkflowVersion;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The version API exposes publish status, archived state and the active version. */
class WorkflowVersionResponseMappingTest {

  private final WorkflowMapperImpl mapper = new WorkflowMapperImpl();
  private final UUID tenantId = UUID.randomUUID();
  private final UUID definitionId = UUID.randomUUID();

  @Test
  void mapsCurrentPublishedVersion() {
    WorkflowVersion version =
        new WorkflowVersion(tenantId, definitionId, 2, "{}", WorkflowStatus.PUBLISHED);

    WorkflowVersionResponse response = mapper.toVersionResponse(version, 2);

    assertThat(response.version()).isEqualTo(2);
    assertThat(response.status()).isEqualTo("PUBLISHED");
    assertThat(response.archived()).isFalse();
    assertThat(response.current()).isTrue();
    assertThat(response.createdAt()).isEqualTo(version.getCreatedAt());
  }

  @Test
  void olderPublishedVersionIsNotCurrent() {
    WorkflowVersion version =
        new WorkflowVersion(tenantId, definitionId, 1, "{}", WorkflowStatus.PUBLISHED);

    WorkflowVersionResponse response = mapper.toVersionResponse(version, 2);

    assertThat(response.status()).isEqualTo("PUBLISHED");
    assertThat(response.current()).isFalse();
  }

  @Test
  void archivedVersionIsNeverCurrent() {
    WorkflowVersion version =
        new WorkflowVersion(tenantId, definitionId, 2, "{}", WorkflowStatus.PUBLISHED);
    version.archive();

    WorkflowVersionResponse response = mapper.toVersionResponse(version, 2);

    assertThat(response.archived()).isTrue();
    assertThat(response.current()).isFalse();
  }

  @Test
  void draftVersionIsNotCurrent() {
    WorkflowVersion version =
        new WorkflowVersion(tenantId, definitionId, 3, "{}", WorkflowStatus.DRAFT);

    WorkflowVersionResponse response = mapper.toVersionResponse(version, 3);

    assertThat(response.status()).isEqualTo("DRAFT");
    assertThat(response.current()).isFalse();
  }
}
