package com.awap.webhook.infrastructure;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Blocks webhook targets that resolve to loopback, link-local, site-local or any-local addresses
 * (SSRF protection).
 */
@Component
public class SsrfGuard {

  public void assertSafe(String url) {
    String host = URI.create(url).getHost();
    if (host == null) {
      throw forbidden(url);
    }
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      for (InetAddress address : addresses) {
        if (isBlocked(address)) {
          throw forbidden(url);
        }
      }
    } catch (AwapException ex) {
      throw ex;
    } catch (Exception ex) {
      throw forbidden(url);
    }
  }

  private boolean isBlocked(InetAddress address) {
    return address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isAnyLocalAddress();
  }

  private AwapException forbidden(String url) {
    return new AwapException(
        ErrorCode.FORBIDDEN,
        HttpStatus.FORBIDDEN,
        "Webhook target is not allowed (SSRF protection): " + url);
  }
}
