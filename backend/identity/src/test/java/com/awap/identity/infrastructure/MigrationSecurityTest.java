package com.awap.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Guards the RLS auth-bypass hardening migration (V31): the SECURITY DEFINER functions must pin
 * {@code search_path = public, pg_temp} (preventing a pg_temp shadow-table hijack) and must return
 * the full table row ({@code RETURNS SETOF <table>}) so future audit columns never break the entity
 * mapping.
 */
class MigrationSecurityTest {

  private static final String V31 = "V31__auth_bypass_harden_search_path.sql";

  private final String sql = readMigration(V31);

  @Test
  void everyAuthBypassFunctionPinsSearchPathToPublicAndPgTemp() {
    assertThat(countOccurrences(sql, "SET search_path = public, pg_temp")).isEqualTo(3);
    assertThat(sql).contains("CREATE FUNCTION awap_find_tenant_by_slug");
    assertThat(sql).contains("CREATE FUNCTION awap_find_user_by_email");
    assertThat(sql).contains("CREATE FUNCTION awap_find_refresh_token");
    // No insecure "SET search_path = public" (without pg_temp) may remain in V31.
    assertThat(sql).doesNotContain("SET search_path = public\n");
  }

  @Test
  void authBypassFunctionsReturnFullTableRows() {
    assertThat(sql).contains("RETURNS SETOF tenants");
    assertThat(sql).contains("RETURNS SETOF users");
    assertThat(sql).contains("RETURNS SETOF refresh_tokens");
  }

  private String readMigration(String filename) {
    try {
      Resource resource =
          new PathMatchingResourcePatternResolver()
              .getResource("classpath:/db/migration/" + filename);
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read migration " + filename, ex);
    }
  }

  private int countOccurrences(String haystack, String needle) {
    int count = 0;
    int index = 0;
    while ((index = haystack.indexOf(needle, index)) != -1) {
      count++;
      index += needle.length();
    }
    return count;
  }
}
