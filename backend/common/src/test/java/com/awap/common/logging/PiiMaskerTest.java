package com.awap.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiMaskerTest {

  @Test
  void masksEmailsButKeepsTheDomain() {
    assertThat(PiiMasker.mask("user alice@example.com failed"))
        .isEqualTo("user a***@example.com failed");
  }

  @Test
  void masksBearerTokensAndSecretKeyValuePairs() {
    assertThat(PiiMasker.mask("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature"))
        .isEqualTo("Authorization: Bearer ***");
    assertThat(PiiMasker.mask("password=s3cret&token=abc123")).isEqualTo("password=***&token=***");
    assertThat(PiiMasker.mask("{\"access_token\":\"abc\",\"name\":\"keep\"}"))
        .isEqualTo("{\"access_token\":\"***\",\"name\":\"keep\"}");
  }

  @Test
  void masksNationalIdsAndHashesLongTokensDeterministically() {
    assertThat(PiiMasker.mask("tckn=12345678901")).isEqualTo("tckn=***");
    String token = "A".repeat(48);
    String first = PiiMasker.mask("token " + token);
    String second = PiiMasker.mask("token " + token);
    assertThat(first).matches("token sha256:[0-9a-f]{8}");
    assertThat(first).isEqualTo(second);
    assertThat(first).doesNotContain(token);
  }

  @Test
  void keepsOperationalIdentifiersReadable() {
    String uuid = "3f1b7c2e-8a4d-4c1e-9f6a-2b3c4d5e6f70";
    assertThat(PiiMasker.mask("trace=" + uuid)).isEqualTo("trace=" + uuid);
  }

  @Test
  void passesNullAndEmptyValuesThrough() {
    assertThat(PiiMasker.mask(null)).isNull();
    assertThat(PiiMasker.mask("")).isEmpty();
  }
}
