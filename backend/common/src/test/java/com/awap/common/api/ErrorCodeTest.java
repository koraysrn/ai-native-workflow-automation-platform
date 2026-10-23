package com.awap.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies the error code catalog has unique codes and non-empty titles. */
class ErrorCodeTest {

  @Test
  void codesAreUnique() {
    Set<String> codes = new HashSet<>();
    for (ErrorCode errorCode : ErrorCode.values()) {
      assertThat(codes.add(errorCode.code())).as("duplicate code: %s", errorCode.code()).isTrue();
    }
  }

  @Test
  void everyCodeHasATitle() {
    for (ErrorCode errorCode : ErrorCode.values()) {
      assertThat(errorCode.code()).matches("AWAP-[A-Z]+-\\d{4}");
      assertThat(errorCode.defaultTitle()).isNotBlank();
    }
  }
}
