package com.awap.workflow.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import java.util.List;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Validates and evaluates SpEL expressions inside a restricted, read-only sandbox. */
@Service
public class SpelExpressionService {

  private static final List<String> FORBIDDEN_PATTERNS =
      List.of(
          "T(",
          "new ",
          ".class",
          "getClass",
          "System",
          "Runtime",
          "ProcessBuilder",
          "ClassLoader",
          "exec",
          "reflect");

  private final SpelExpressionParser parser = new SpelExpressionParser();

  public void validate(String expression) {
    if (expression == null || expression.isBlank()) {
      return;
    }
    for (String pattern : FORBIDDEN_PATTERNS) {
      if (expression.contains(pattern)) {
        throw new AwapException(
            ErrorCode.VALIDATION_FAILED,
            HttpStatus.BAD_REQUEST,
            "Expression contains a forbidden pattern: " + pattern);
      }
    }
    try {
      parser.parseExpression(expression);
    } catch (ParseException ex) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "Expression is invalid.");
    }
  }

  public Object evaluate(String expression, Object root) {
    Expression parsed = parser.parseExpression(expression);
    EvaluationContext context =
        SimpleEvaluationContext.forReadOnlyDataBinding().withRootObject(root).build();
    return parsed.getValue(context);
  }
}
