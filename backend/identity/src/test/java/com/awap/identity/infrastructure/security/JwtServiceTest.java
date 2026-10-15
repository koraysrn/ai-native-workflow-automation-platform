package com.awap.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.identity.domain.Role;
import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";

  @Test
  void generateAndParseAccessTokenRoundTrip() {
    JwtService service =
        new JwtService(
            new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(7)));
    UUID userId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();

    String token = service.generateAccessToken(userId, tenantId, "admin@acme.com", Role.EMPLOYEE);
    Claims claims = service.parse(token);

    assertThat(claims.getSubject()).isEqualTo(userId.toString());
    assertThat(claims.get("tenantId", String.class)).isEqualTo(tenantId.toString());
    assertThat(claims.get("role", String.class)).isEqualTo("EMPLOYEE");
    assertThat(claims.get("email", String.class)).isEqualTo("admin@acme.com");
  }
}
