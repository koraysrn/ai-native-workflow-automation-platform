package com.awap.script.application;

import com.awap.script.domain.ScriptSandboxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sandbox for the SCRIPT node.
 *
 * <p>The sandbox is deny-by-construction: scripts are parsed into a tiny expression grammar
 * (arithmetic, comparisons, boolean logic, string concatenation and a whitelist of functions over a
 * typed {@code ctx} map). There is no way to express imports, reflection, method calls, file or
 * network access, and no identifier other than {@code ctx}/{@code true}/{@code false} is resolved —
 * so host APIs and environment variables are unreachable even without a JVM security manager.
 * Execution is bounded by a mandatory timeout, a script-size cap and an output-size cap.
 */
@Component
public class ScriptSandbox {

  /** Maximum accepted script size in characters. */
  public static final int MAX_SCRIPT_CHARS = 4_000;

  private static final Set<String> FUNCTIONS = Set.of("len", "upper", "lower", "coalesce", "abs");

  private final long timeoutMs;
  private final int maxOutputChars;
  private final ExecutorService executor;
  private final long testDelayMs;

  @Autowired
  public ScriptSandbox(
      @Value("${awap.script.timeout-ms:10000}") long timeoutMs,
      @Value("${awap.script.max-output-chars:8192}") int maxOutputChars) {
    this(timeoutMs, maxOutputChars, Executors.newVirtualThreadPerTaskExecutor(), 0);
  }

  /** Visible for tests so the timeout/output-cap seams can be exercised deterministically. */
  public ScriptSandbox(
      long timeoutMs, int maxOutputChars, ExecutorService executor, long testDelayMs) {
    this.timeoutMs = Math.max(1, timeoutMs);
    this.maxOutputChars = Math.max(1, maxOutputChars);
    this.executor = executor;
    this.testDelayMs = Math.max(0, testDelayMs);
  }

  /** Evaluates the script against the context with a hard timeout. */
  public Result evaluate(String script, Map<String, Object> context) {
    if (script == null || script.isBlank()) {
      throw new ScriptSandboxException("Script must not be empty.");
    }
    if (script.length() > MAX_SCRIPT_CHARS) {
      throw new ScriptSandboxException("Script exceeds " + MAX_SCRIPT_CHARS + " characters.");
    }
    Future<Object> future =
        executor.submit(
            () -> {
              if (testDelayMs > 0) {
                Thread.sleep(testDelayMs);
              }
              return new Parser(script, context == null ? Map.of() : context).parse();
            });
    try {
      Object value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
      String rendered = String.valueOf(value);
      if (rendered.length() > maxOutputChars) {
        throw new ScriptSandboxException(
            "Script output exceeds " + maxOutputChars + " characters.");
      }
      return new Result(value, rendered);
    } catch (TimeoutException ex) {
      future.cancel(true);
      throw new ScriptSandboxException("Script timed out after " + timeoutMs + "ms.", true);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ScriptSandboxException("Script execution was interrupted.", true);
    } catch (ExecutionException ex) {
      if (ex.getCause() instanceof ScriptSandboxException sandbox) {
        throw sandbox;
      }
      throw new ScriptSandboxException(
          "Script failed: "
              + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
    }
  }

  /** Sandboxed value plus its string rendering. */
  public record Result(Object value, String rendered) {}

  /** Recursive-descent parser/evaluator; every unsupported construct is rejected. */
  private static final class Parser {

    private final String script;
    private final Map<String, Object> context;
    private int index;

    Parser(String script, Map<String, Object> context) {
      this.script = script;
      this.context = context;
    }

    Object parse() {
      Object value = parseOr();
      skipWhitespace();
      if (index < script.length()) {
        throw new ScriptSandboxException(
            "Unexpected character at position " + index + ": " + script.charAt(index));
      }
      return value;
    }

    private Object parseOr() {
      Object left = parseAnd();
      while (matchWord("or")) {
        left = truthy(left) || truthy(parseAnd());
      }
      return left;
    }

    private Object parseAnd() {
      Object left = parseComparison();
      while (matchWord("and")) {
        left = truthy(left) && truthy(parseComparison());
      }
      return left;
    }

    private Object parseComparison() {
      Object left = parseAdditive();
      for (String operator : List.of("==", "!=", "<=", ">=", "<", ">")) {
        if (match(operator)) {
          Object right = parseAdditive();
          return compare(operator, left, right);
        }
      }
      return left;
    }

    private Object parseAdditive() {
      Object left = parseMultiplicative();
      while (true) {
        if (match("+")) {
          left = add(left, parseMultiplicative());
        } else if (match("-")) {
          left = arithmetic("-", left, parseMultiplicative());
        } else {
          return left;
        }
      }
    }

    private Object parseMultiplicative() {
      Object left = parseUnary();
      while (true) {
        if (match("*")) {
          left = arithmetic("*", left, parseUnary());
        } else if (match("/")) {
          left = arithmetic("/", left, parseUnary());
        } else if (match("%")) {
          left = arithmetic("%", left, parseUnary());
        } else {
          return left;
        }
      }
    }

    private Object parseUnary() {
      if (match("not")) {
        return !truthy(parseUnary());
      }
      if (match("-")) {
        return -number(parseUnary());
      }
      return parsePrimary();
    }

    private Object parsePrimary() {
      skipWhitespace();
      if (index >= script.length()) {
        throw new ScriptSandboxException("Unexpected end of script.");
      }
      char current = script.charAt(index);
      if (current == '(') {
        index++;
        Object value = parseOr();
        expect(')');
        return value;
      }
      if (current == '\'' || current == '"') {
        return readString(current);
      }
      if (Character.isDigit(current)) {
        return readNumber();
      }
      if (Character.isLetter(current) || current == '_') {
        return readIdentifier();
      }
      throw new ScriptSandboxException(
          "Unsupported character at position " + index + ": " + current);
    }

    private Object readIdentifier() {
      String name = readWord();
      if ("true".equals(name)) {
        return Boolean.TRUE;
      }
      if ("false".equals(name)) {
        return Boolean.FALSE;
      }
      if ("null".equals(name)) {
        return null;
      }
      if (FUNCTIONS.contains(name)) {
        expect('(');
        List<Object> arguments = readArguments();
        return call(name, arguments);
      }
      if ("ctx".equals(name)) {
        return readContextPath();
      }
      throw new ScriptSandboxException(
          "Identifier '" + name + "' is not allowed in the script sandbox.");
    }

    private Object readContextPath() {
      Object value = context;
      while (true) {
        skipWhitespace();
        if (index >= script.length() || script.charAt(index) != '.') {
          return value;
        }
        index++;
        skipWhitespace();
        String field = readWord();
        if (field.isEmpty()) {
          throw new ScriptSandboxException("Expected a context field after '.'.");
        }
        skipWhitespace();
        if (index < script.length() && script.charAt(index) == '(') {
          throw new ScriptSandboxException("Method calls are not allowed in the script sandbox.");
        }
        value = value instanceof Map<?, ?> map ? map.get(field) : null;
      }
    }

    private List<Object> readArguments() {
      List<Object> arguments = new ArrayList<>();
      skipWhitespace();
      if (match(")")) {
        return arguments;
      }
      arguments.add(parseOr());
      while (match(",")) {
        arguments.add(parseOr());
      }
      expect(')');
      return arguments;
    }

    private Object call(String name, List<Object> arguments) {
      return switch (name) {
        case "len" -> arguments.isEmpty() ? 0 : lengthOf(arguments.get(0));
        case "upper" ->
            arguments.isEmpty() ? "" : String.valueOf(arguments.get(0)).toUpperCase(Locale.ROOT);
        case "lower" ->
            arguments.isEmpty() ? "" : String.valueOf(arguments.get(0)).toLowerCase(Locale.ROOT);
        case "abs" -> arguments.isEmpty() ? 0.0 : Math.abs(number(arguments.get(0)));
        case "coalesce" ->
            arguments.stream().filter(argument -> argument != null).findFirst().orElse(null);
        default -> throw new ScriptSandboxException("Function '" + name + "' is not allowed.");
      };
    }

    private static int lengthOf(Object value) {
      if (value == null) {
        return 0;
      }
      return value instanceof String string ? string.length() : String.valueOf(value).length();
    }

    private Object add(Object left, Object right) {
      if (left instanceof String || right instanceof String) {
        return String.valueOf(left) + right;
      }
      return arithmetic("+", left, right);
    }

    private Object arithmetic(String operator, Object left, Object right) {
      double leftNumber = number(left);
      double rightNumber = number(right);
      double result =
          switch (operator) {
            case "+" -> leftNumber + rightNumber;
            case "-" -> leftNumber - rightNumber;
            case "*" -> leftNumber * rightNumber;
            case "/" -> {
              if (rightNumber == 0) {
                throw new ScriptSandboxException("Division by zero.");
              }
              yield leftNumber / rightNumber;
            }
            case "%" -> {
              if (rightNumber == 0) {
                throw new ScriptSandboxException("Division by zero.");
              }
              yield leftNumber % rightNumber;
            }
            default -> throw new ScriptSandboxException("Unsupported operator " + operator);
          };
      return result == Math.rint(result) && !Double.isInfinite(result)
          ? (Object) (long) result
          : (Object) result;
    }

    private Object compare(String operator, Object left, Object right) {
      int comparison =
          left instanceof Number && right instanceof Number
              ? Double.compare(number(left), number(right))
              : String.valueOf(left).compareTo(String.valueOf(right));
      return switch (operator) {
        case "==" -> left == null ? right == null : left.equals(right);
        case "!=" -> left == null ? right != null : !left.equals(right);
        case "<" -> comparison < 0;
        case "<=" -> comparison <= 0;
        case ">" -> comparison > 0;
        case ">=" -> comparison >= 0;
        default -> throw new ScriptSandboxException("Unsupported comparison " + operator);
      };
    }

    private static boolean truthy(Object value) {
      if (value instanceof Boolean bool) {
        return bool;
      }
      if (value instanceof Number number) {
        return number.doubleValue() != 0;
      }
      return value != null && !String.valueOf(value).isBlank();
    }

    private static double number(Object value) {
      if (value instanceof Number number) {
        return number.doubleValue();
      }
      if (value instanceof String string) {
        try {
          return Double.parseDouble(string);
        } catch (NumberFormatException ex) {
          throw new ScriptSandboxException("'" + string + "' is not numeric.");
        }
      }
      throw new ScriptSandboxException("Numeric value expected but got: " + value);
    }

    private Object readNumber() {
      int start = index;
      while (index < script.length()
          && (Character.isDigit(script.charAt(index)) || script.charAt(index) == '.')) {
        index++;
      }
      String raw = script.substring(start, index);
      try {
        return raw.contains(".") ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
      } catch (NumberFormatException ex) {
        throw new ScriptSandboxException("Invalid number: " + raw);
      }
    }

    private String readString(char quote) {
      index++;
      StringBuilder value = new StringBuilder();
      while (index < script.length()) {
        char current = script.charAt(index++);
        if (current == '\\' && index < script.length()) {
          value.append(script.charAt(index++));
          continue;
        }
        if (current == quote) {
          return value.toString();
        }
        value.append(current);
      }
      throw new ScriptSandboxException("Unterminated string literal.");
    }

    private String readWord() {
      skipWhitespace();
      int start = index;
      while (index < script.length()
          && (Character.isLetterOrDigit(script.charAt(index)) || script.charAt(index) == '_')) {
        index++;
      }
      return script.substring(start, index);
    }

    private boolean matchWord(String word) {
      skipWhitespace();
      int start = index;
      int end = start + word.length();
      if (end > script.length() || !script.regionMatches(true, start, word, 0, word.length())) {
        return false;
      }
      if (end < script.length()
          && (Character.isLetterOrDigit(script.charAt(end)) || script.charAt(end) == '_')) {
        return false;
      }
      index = end;
      return true;
    }

    private boolean match(String token) {
      skipWhitespace();
      if (!script.startsWith(token, index)) {
        return false;
      }
      index += token.length();
      return true;
    }

    private void expect(char expected) {
      skipWhitespace();
      if (index >= script.length() || script.charAt(index) != expected) {
        throw new ScriptSandboxException("Expected '" + expected + "' at position " + index + ".");
      }
      index++;
    }

    private void skipWhitespace() {
      while (index < script.length() && Character.isWhitespace(script.charAt(index))) {
        index++;
      }
    }
  }
}
