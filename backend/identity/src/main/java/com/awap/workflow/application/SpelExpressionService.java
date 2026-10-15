package com.awap.workflow.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Validates and evaluates SpEL expressions inside a restricted, read-only, time-boxed sandbox. */
@Service
public class SpelExpressionService {

  private static final long EVALUATION_TIMEOUT_MILLIS = 100;

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

  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

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
        SimpleEvaluationContext.forReadOnlyDataBinding()
            .withInstanceMethods()
            .withRootObject(root)
            .build();
    return evaluateWithTimeout(parsed, context);
  }

  private Object evaluateWithTimeout(Expression expression, EvaluationContext context) {
    Future<Object> future = EXECUTOR.submit(() -> expression.getValue(context));
    try {
      return future.get(EVALUATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      future.cancel(true);
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.REQUEST_TIMEOUT,
          "Expression evaluation timed out.");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED,
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Expression evaluation interrupted.");
    } catch (ExecutionException ex) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "Expression evaluation failed.");
    }
  }
}
