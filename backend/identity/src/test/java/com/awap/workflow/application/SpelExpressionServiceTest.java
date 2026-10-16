package com.awap.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import org.junit.jupiter.api.Test;

class SpelExpressionServiceTest {

  private final SpelExpressionService service = new SpelExpressionService();

  @Test
  void acceptsSimpleComparisonExpression() {
    assertThatCode(() -> service.validate("amount > 100")).doesNotThrowAnyException();
  }

  @Test
  void rejectsRuntimeAccess() {
    assertThatThrownBy(() -> service.validate("T(java.lang.Runtime).getRuntime().exec('x')"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void rejectsTypeReference() {
    assertThatThrownBy(() -> service.validate("T(java.lang.System)"))
        .isInstanceOf(AwapException.class);
  }

  @Test
  void evaluatesSimpleExpression() {
    Object result = service.evaluate("amount + 1", new Holder(10));
    assertThat(result).isEqualTo(11);
  }

  @Test
  void evaluatesWhitelistedFunction() {
    Object result = service.evaluate("hasRole('manager', 'manager')", new SpelFunctions());
    assertThat(result).isEqualTo(true);
  }

  static final class Holder {
    public final int amount;

    Holder(int amount) {
      this.amount = amount;
    }
  }
}
