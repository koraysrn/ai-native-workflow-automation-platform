package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.workflow.domain.NodeType;
import org.junit.jupiter.api.Test;

class NodeTimeoutPolicyTest {

  @Test
  void externalCallsHaveAHardGuarantee() {
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.WEBHOOK))
        .isEqualTo(NodeTimeoutEnforcement.ENFORCED);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.AI))
        .isEqualTo(NodeTimeoutEnforcement.ENFORCED);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeTimeoutPolicy.SCRIPT_NODE_TYPE))
        .isEqualTo(NodeTimeoutEnforcement.ENFORCED);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.WEBHOOK).hardGuarantee()).isTrue();
  }

  @Test
  void transactionBoundNodesAreBestEffort() {
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.FORM))
        .isEqualTo(NodeTimeoutEnforcement.BEST_EFFORT);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.APPROVAL))
        .isEqualTo(NodeTimeoutEnforcement.BEST_EFFORT);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.TASK))
        .isEqualTo(NodeTimeoutEnforcement.BEST_EFFORT);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.SUB_PROCESS))
        .isEqualTo(NodeTimeoutEnforcement.BEST_EFFORT);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.DELAY))
        .isEqualTo(NodeTimeoutEnforcement.BEST_EFFORT);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.DECISION))
        .isEqualTo(NodeTimeoutEnforcement.BEST_EFFORT);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.CONDITION))
        .isEqualTo(NodeTimeoutEnforcement.BEST_EFFORT);
    assertThat(NodeTimeoutPolicy.enforcementFor(NodeType.FORM).hardGuarantee()).isFalse();
  }

  @Test
  void structuralNodesAreUnsupported() {
    for (NodeType type :
        new NodeType[] {
          NodeType.START,
          NodeType.END,
          NodeType.PARALLEL,
          NodeType.PARALLEL_GATEWAY,
          NodeType.MERGE,
          NodeType.MERGE_GATEWAY
        }) {
      assertThat(NodeTimeoutPolicy.enforcementFor(type))
          .as("enforcement of %s", type)
          .isEqualTo(NodeTimeoutEnforcement.UNSUPPORTED);
    }
  }

  @Test
  void everyNodeTypeIsExplicitlyMapped() {
    for (NodeType type : NodeType.values()) {
      assertThat(NodeTimeoutPolicy.enforcementTable())
          .as("policy must list %s", type)
          .containsKey(type.name());
    }
    assertThat(NodeTimeoutPolicy.enforcementTable()).hasSize(NodeType.values().length);
    assertThat(NodeTimeoutPolicy.enforcementTable())
        .containsKey(NodeTimeoutPolicy.SCRIPT_NODE_TYPE)
        .containsKey(NodeType.SCRIPT.name());
  }

  @Test
  void unknownAndBlankTypesAreUnsupported() {
    assertThat(NodeTimeoutPolicy.enforcementFor("GRAPHQL"))
        .isEqualTo(NodeTimeoutEnforcement.UNSUPPORTED);
    assertThat(NodeTimeoutPolicy.enforcementFor((String) null))
        .isEqualTo(NodeTimeoutEnforcement.UNSUPPORTED);
    assertThat(NodeTimeoutPolicy.enforcementFor("  "))
        .isEqualTo(NodeTimeoutEnforcement.UNSUPPORTED);
    assertThat(NodeTimeoutPolicy.enforcementFor((NodeType) null))
        .isEqualTo(NodeTimeoutEnforcement.UNSUPPORTED);
  }

  @Test
  void typeNamesAreCaseInsensitive() {
    assertThat(NodeTimeoutPolicy.enforcementFor("webhook"))
        .isEqualTo(NodeTimeoutEnforcement.ENFORCED);
    assertThat(NodeTimeoutPolicy.enforcementFor("script"))
        .isEqualTo(NodeTimeoutEnforcement.ENFORCED);
  }
}
