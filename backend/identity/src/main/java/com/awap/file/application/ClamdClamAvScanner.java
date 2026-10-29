package com.awap.file.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.domain.FileScanStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Real ClamAV scanner: streams the supplied content to a clamd daemon over the {@code INSTREAM}
 * protocol and interprets the {@code stream: OK} / {@code stream: <name> FOUND} response.
 */
@Component
@ConditionalOnProperty(name = "awap.clamav.enabled", havingValue = "true")
public class ClamdClamAvScanner implements ClamAvScanner {

  private final String host;
  private final int port;
  private final int timeoutMs;

  public ClamdClamAvScanner(
      @Value("${awap.clamav.host:localhost}") String host,
      @Value("${awap.clamav.port:3310}") int port,
      @Value("${awap.clamav.timeout-ms:10000}") int timeoutMs) {
    this.host = host;
    this.port = port;
    this.timeoutMs = timeoutMs;
  }

  @Override
  public FileScanStatus scan(InputStream content) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), timeoutMs);
      socket.setSoTimeout(timeoutMs);
      OutputStream out = socket.getOutputStream();
      out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
      out.flush();

      ClamAvStream.pump(content, out);

      String response = readResponse(socket.getInputStream());
      if (response.startsWith("stream: OK")) {
        return FileScanStatus.CLEAN;
      }
      if (response.contains("FOUND")) {
        return FileScanStatus.INFECTED;
      }
      throw new AwapException(
          ErrorCode.FILE_SCAN_FAILED,
          HttpStatus.SERVICE_UNAVAILABLE,
          "Antivirus scan returned an unexpected response.");
    } catch (AwapException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.FILE_SCAN_FAILED,
          HttpStatus.SERVICE_UNAVAILABLE,
          "Antivirus scan failed: " + rootMessage(ex));
    }
  }

  private String rootMessage(Exception ex) {
    String message = ex.getMessage();
    return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
  }

  private String readResponse(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
    int b;
    while ((b = in.read()) != -1) {
      if (b == 0) {
        break;
      }
      buffer.write(b);
    }
    return buffer.toString(StandardCharsets.US_ASCII);
  }
}
