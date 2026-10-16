package com.awap.file.application;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.domain.FileScanStatus;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
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
 * Real ClamAV scanner: streams the MinIO object to a clamd daemon over the {@code INSTREAM}
 * protocol and interprets the {@code stream: OK} / {@code stream: <name> FOUND} response.
 */
@Component
@ConditionalOnProperty(name = "awap.clamav.enabled", havingValue = "true")
public class ClamdClamAvScanner implements ClamAvScanner {

  private final MinioClient minioClient;
  private final String host;
  private final int port;
  private final int timeoutMs;

  public ClamdClamAvScanner(
      MinioClient minioClient,
      @Value("${awap.clamav.host:localhost}") String host,
      @Value("${awap.clamav.port:3310}") int port,
      @Value("${awap.clamav.timeout-ms:10000}") int timeoutMs) {
    this.minioClient = minioClient;
    this.host = host;
    this.port = port;
    this.timeoutMs = timeoutMs;
  }

  @Override
  public FileScanStatus scan(String bucket, String objectKey) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), timeoutMs);
      socket.setSoTimeout(timeoutMs);
      OutputStream out = socket.getOutputStream();
      out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
      out.flush();

      try (InputStream in =
          minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(chunkHeader(read));
          out.write(buffer, 0, read);
        }
      }
      out.write(new byte[] {0, 0, 0, 0});
      out.flush();

      String response = readResponse(socket.getInputStream());
      return response.startsWith("stream: OK") ? FileScanStatus.CLEAN : FileScanStatus.INFECTED;
    } catch (Exception ex) {
      throw new AwapException(
          ErrorCode.GENERIC_INTERNAL_ERROR,
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Antivirus scan failed.");
    }
  }

  private byte[] chunkHeader(int length) {
    return new byte[] {
      (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length
    };
  }

  private String readResponse(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
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
