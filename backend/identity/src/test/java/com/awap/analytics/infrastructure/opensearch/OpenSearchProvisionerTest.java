package com.awap.analytics.infrastructure.opensearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenSearchProvisionerTest {

  private final FakeOpenSearchTransport transport = new FakeOpenSearchTransport();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final OpenSearchProvisioner provisioner =
      new OpenSearchProvisioner(
          OpenSearchTestSupport.properties(), transport, new ObjectMapper(), meters);

  @Test
  void provisionsIsmPolicyAndTemplatesWithConfiguredRetentionAndMappings() {
    assertThat(provisioner.provision()).isTrue();

    List<FakeOpenSearchTransport.Request> requests = transport.requests();
    assertThat(requests).hasSize(3);

    FakeOpenSearchTransport.Request policy = requests.get(0);
    assertThat(policy.method()).isEqualTo("PUT");
    assertThat(policy.path()).isEqualTo("/_plugins/_ism/policies/awap-analytics-ism");
    assertThat(policy.body())
        .contains("\"min_index_age\":\"7d\"")
        .contains("\"min_index_age\":\"180d\"")
        .contains("hot 7d, warm 30d, delete 180d")
        .contains("\"index_patterns\":[\"awap-analytics-*\"]");

    FakeOpenSearchTransport.Request template = requests.get(1);
    assertThat(template.path()).isEqualTo("/_index_template/awap-analytics-template");
    assertThat(template.body())
        .contains("\"dynamic\":\"strict\"")
        .contains("\"tenantId\":{\"type\":\"keyword\"}")
        .contains("\"eventType\":{\"type\":\"keyword\"}")
        .contains("\"workflowInstanceId\":{\"type\":\"keyword\"}")
        .contains("\"taskId\":{\"type\":\"keyword\"}")
        .contains("\"durationMs\":{\"type\":\"long\"}")
        .contains("\"index.plugins.index_state_management.policy_id\":\"awap-analytics-ism\"");

    FakeOpenSearchTransport.Request dlqTemplate = requests.get(2);
    assertThat(dlqTemplate.path()).isEqualTo("/_index_template/awap-analytics-dlq-template");
    assertThat(dlqTemplate.body()).contains("\"awap-analytics-dlq-*\"");

    assertThat(provisioned("ism_policy", "success")).isEqualTo(1d);
    assertThat(provisioned("index_template", "success")).isEqualTo(1d);
    assertThat(provisioned("dlq_index_template", "success")).isEqualTo(1d);
  }

  @Test
  void repeatedProvisioningIsIdempotent() {
    assertThat(provisioner.provision()).isTrue();
    assertThat(provisioner.provision()).isTrue();

    assertThat(transport.requests()).hasSize(6);
    assertThat(transport.requests().get(0).path()).isEqualTo(transport.requests().get(3).path());
    assertThat(transport.requests().get(1).path()).isEqualTo(transport.requests().get(4).path());
    assertThat(transport.requests().get(2).path()).isEqualTo(transport.requests().get(5).path());
  }

  @Test
  void treatsConflictAsAlreadyProvisioned() {
    transport.defaultResponse(new OpenSearchResponse(409, "{}"));

    assertThat(provisioner.provision()).isTrue();
    assertThat(provisioned("ism_policy", "success")).isEqualTo(1d);
    assertThat(provisioned("index_template", "success")).isEqualTo(1d);
    assertThat(provisioned("dlq_index_template", "success")).isEqualTo(1d);
  }

  @Test
  void reportsFailureWhenOpenSearchRejectsProvisioning() {
    transport.defaultResponse(new OpenSearchResponse(500, "{}"));

    assertThat(provisioner.provision()).isFalse();
    assertThat(provisioned("ism_policy", "failure")).isEqualTo(1d);
    assertThat(provisioned("index_template", "failure")).isEqualTo(1d);
    assertThat(provisioned("dlq_index_template", "failure")).isEqualTo(1d);
  }

  private double provisioned(String component, String outcome) {
    return meters
        .get("awap.analytics.sink.provisioning")
        .tags("component", component, "outcome", outcome)
        .counter()
        .count();
  }
}
