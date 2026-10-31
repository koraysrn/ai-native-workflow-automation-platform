package com.awap.workflow.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  /** Upper bound shared with the designer so a pathological expression cannot freeze the UI. */
  public static final int MAX_EXPRESSION_LENGTH = 2000;

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

  /**
   * Collects every reason the expression cannot be persisted.
   *
   * <p>A blank expression is a valid, unconditional transition and therefore yields no problems.
   * The length guard runs before the denylist and parsing so an oversized payload is rejected with
   * a single, clear message instead of a parser-specific failure.
   */
  public List<String> problems(String expression) {
    List<String> problems = new ArrayList<>();
    if (expression == null || expression.isBlank()) {
      return problems;
    }
    if (expression.length() > MAX_EXPRESSION_LENGTH) {
      problems.add(
          "Expression exceeds the maximum length of " + MAX_EXPRESSION_LENGTH + " characters.");
      return problems;
    }
    for (String pattern : FORBIDDEN_PATTERNS) {
      if (expression.contains(pattern)) {
        problems.add("Expression contains a forbidden pattern: " + pattern);
      }
    }
    try {
      parser.parseExpression(expression);
    } catch (ParseException ex) {
      problems.add("Expression is invalid.");
    }
    return problems;
  }

  public void validate(String expression) {
    List<String> problems = problems(expression);
    if (!problems.isEmpty()) {
      throw new AwapException(
          ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, problems.getFirst());
    }
  }

  public Object evaluate(String expression, Object root) {
    Expression parsed = parser.parseExpression(expression);
    EvaluationContext context =
        SimpleEvaluationContext.forReadOnlyDataBinding()
            .withInstanceMethods()
            .withRootObject(root)
            .build();
    registerWhitelistedFunctions(context);
    return evaluateWithTimeout(parsed, context);
  }

  /** Evaluates an expression with named variables (e.g. {@code #amount > 100}) in the sandbox. */
  public Object evaluate(String expression, Map<String, Object> variables) {
    Expression parsed = parser.parseExpression(expression);
    EvaluationContext context =
        SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods().build();
    variables.forEach(context::setVariable);
    registerWhitelistedFunctions(context);
    return evaluateWithTimeout(parsed, context);
  }

  /** Exposes the whitelisted functions as {@code #fn.<name>(...)} inside the sandbox. */
  private void registerWhitelistedFunctions(EvaluationContext context) {
    context.setVariable("fn", new SpelFunctions());
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
