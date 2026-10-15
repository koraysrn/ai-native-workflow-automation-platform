package com.awap.workflow.application;

import java.util.Collection;

/** Whitelisted functions exposed to workflow expressions. */
public final class SpelFunctions {

  public boolean hasRole(Object value, String role) {
    return value != null && role.equalsIgnoreCase(String.valueOf(value));
  }

  public boolean hasAnyRole(Object value, String... roles) {
    if (value == null) {
      return false;
    }
    String actual = String.valueOf(value);
    for (String role : roles) {
      if (role.equalsIgnoreCase(actual)) {
        return true;
      }
    }
    return false;
  }

  public boolean matches(String input, String regex) {
    return input != null && input.matches(regex);
  }

  public int length(Object value) {
    return value == null ? 0 : String.valueOf(value).length();
  }

  public boolean isEmpty(Object value) {
    return value == null || String.valueOf(value).isEmpty();
  }

  public int size(Collection<?> collection) {
    return collection == null ? 0 : collection.size();
  }
}
