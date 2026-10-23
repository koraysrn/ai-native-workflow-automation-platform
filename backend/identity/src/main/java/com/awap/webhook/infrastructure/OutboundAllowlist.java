package com.awap.webhook.infrastructure;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Restricts outbound webhook targets to an explicit host allowlist (fail-closed). */
@Component
public class OutboundAllowlist {

  private final Set<String> allowedHosts;

  public OutboundAllowlist(@Value("${awap.webhook.allowed-hosts:}") String allowedHosts) {
    this.allowedHosts =
        Arrays.stream(allowedHosts.split(","))
            .map(String::trim)
            .filter(host -> !host.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  public void assertAllowed(String url) {
    String host = URI.create(url).getHost();
    if (host == null || !allowedHosts.contains(host)) {
      throw new AwapException(
          ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "Webhook target is not allowlisted: " + url);
    }
  }
}
