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
    final String host;
    try {
      host = URI.create(url).getHost();
    } catch (IllegalArgumentException ex) {
      throw forbidden(url);
    }
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
    if (address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isAnyLocalAddress()) {
      return true;
    }
    return isBlockedIpv4Mapped(address);
  }

  /**
   * Detects IPv4 addresses embedded in IPv6 form (e.g. {@code ::ffff:127.0.0.1}) which some JDK
   * versions expose as an {@link Inet6Address} whose standard reachability checks do not classify
   * the embedded IPv4 range correctly.
   */
  private boolean isBlockedIpv4Mapped(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    if ((bytes[10] & 0xff) != 0xff || (bytes[11] & 0xff) != 0xff) {
      return false;
    }
    return isBlockedIpv4(bytes[12] & 0xff, bytes[13] & 0xff, bytes[14] & 0xff, bytes[15] & 0xff);
  }

  private boolean isBlockedIpv4(int a, int b, int c, int d) {
    if (a == 127 || a == 10 || a == 0) {
      return true; // loopback, private 10/8, any-local 0/8
    }
    if (a == 169 && b == 254) {
      return true; // link-local
    }
    if (a == 172 && b >= 16 && b <= 31) {
      return true; // private 172.16/12
    }
    if (a == 192 && b == 168) {
      return true; // private 192.168/16
    }
    if (a == 100 && b >= 64 && b <= 127) {
      return true; // CGNAT 100.64/10
    }
    if (a == 192 && b == 0 && (c == 0 || c == 2)) {
      return true; // 192.0.0.0/24, 192.0.2.0/24
    }
    if (a == 198 && (b == 18 || b == 19) || (a == 198 && b == 51 && c == 100)) {
      return true; // benchmark ranges
    }
    if (a == 203 && b == 0 && c == 113) {
      return true; // 203.0.113.0/24
    }
    return a >= 224;
  }

  private AwapException forbidden(String url) {
    return new AwapException(
        ErrorCode.FORBIDDEN,
        HttpStatus.FORBIDDEN,
        "Webhook target is not allowed (SSRF protection): " + url);
  }
}
