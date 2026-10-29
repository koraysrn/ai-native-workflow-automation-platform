package com.awap.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.engine.domain.ReplayPayloadSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonReplayPayloadSummarizerTest {

  private final JsonReplayPayloadSummarizer summarizer =
      new JsonReplayPayloadSummarizer(new ObjectMapper());

  @Test
  void objectSummaryContainsKeysButNoValues() {
    ReplayPayloadSummary summary = summarizer.summarize("{\"iban\":\"TR123\",\"amount\":42}");

    assertThat(summary.validJson()).isTrue();
    assertThat(summary.summary()).contains("keys=amount,iban").doesNotContain("TR123");
    assertThat(summary.summary()).contains("len=28");
    assertThat(summary.summary()).contains("sha256=");
  }

  @Test
  void arraySummaryReportsSize() {
    ReplayPayloadSummary summary = summarizer.summarize("[1,2,3]");

    assertThat(summary.validJson()).isTrue();
    assertThat(summary.summary()).contains("keys=array[3]");
  }

  @Test
  void scalarSummaryIsMarkedAsScalar() {
    ReplayPayloadSummary summary = summarizer.summarize("\"plain\"");

    assertThat(summary.validJson()).isTrue();
    assertThat(summary.summary()).contains("keys=scalar");
  }

  @Test
  void malformedJsonIsFlaggedInsteadOfThrowing() {
    ReplayPayloadSummary summary = summarizer.summarize("{not-json");

    assertThat(summary.validJson()).isFalse();
    assertThat(summary.summary()).isEqualTo("unparsed;length=9");
  }

  @Test
  void emptyPayloadProducesNoSummary() {
    assertThat(summarizer.summarize(null).summary()).isEmpty();
    assertThat(summarizer.summarize("").validJson()).isTrue();
    assertThat(summarizer.summarize(" ").summary()).isEmpty();
  }

  @Test
  void summaryIsDeterministic() {
    String payload = "{\"b\":1,\"a\":{\"nested\":true}}";

    assertThat(summarizer.summarize(payload).summary())
        .isEqualTo(summarizer.summarize(payload).summary());
  }
}
