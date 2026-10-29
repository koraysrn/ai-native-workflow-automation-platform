package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.common.api.AwapException;
import com.awap.common.api.ErrorCode;
import com.awap.file.domain.FileScanStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Verifies the real clamd INSTREAM implementation against a fake clamd + EICAR fixture. */
class ClamdClamAvScannerTest {

  @Test
  void cleanStreamIsReportedClean() throws Exception {
    try (FakeClamd clamd = new FakeClamd(false, "stream: OK")) {
      ClamdClamAvScanner scanner = new ClamdClamAvScanner("127.0.0.1", clamd.port(), 2_000);

      FileScanStatus result =
          scanner.scan(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

      assertThat(result).isEqualTo(FileScanStatus.CLEAN);
    }
  }

  @Test
  void eicarFixtureIsReportedInfected() throws Exception {
    byte[] eicar = getClass().getResourceAsStream("/fixtures/eicar.txt").readAllBytes();
    try (FakeClamd clamd = new FakeClamd(false, "stream: Eicar-Test-Signature FOUND")) {
      ClamdClamAvScanner scanner = new ClamdClamAvScanner("127.0.0.1", clamd.port(), 2_000);

      FileScanStatus result = scanner.scan(new ByteArrayInputStream(eicar));

      assertThat(result).isEqualTo(FileScanStatus.INFECTED);
    }
  }

  @Test
  void clamdDownIsReportedAsScanFailed() {
    ClamdClamAvScanner scanner = new ClamdClamAvScanner("127.0.0.1", 1, 200);

    assertThatThrownBy(() -> scanner.scan(new ByteArrayInputStream(new byte[] {1})))
        .isInstanceOfSatisfying(
            AwapException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_SCAN_FAILED);
              assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            });
  }

  @Test
  void clamdTimeoutIsReportedAsScanFailed() throws Exception {
    try (FakeClamd clamd = new FakeClamd(true, "stream: OK")) {
      ClamdClamAvScanner scanner = new ClamdClamAvScanner("127.0.0.1", clamd.port(), 200);

      assertThatThrownBy(() -> scanner.scan(new ByteArrayInputStream(new byte[] {1})))
          .isInstanceOfSatisfying(
              AwapException.class,
              ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_SCAN_FAILED);
                assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              });
    }
  }

  @Test
  void unexpectedResponseIsReportedAsScanFailed() throws Exception {
    try (FakeClamd clamd = new FakeClamd(false, "stream: WHATEVER")) {
      ClamdClamAvScanner scanner = new ClamdClamAvScanner("127.0.0.1", clamd.port(), 2_000);

      assertThatThrownBy(() -> scanner.scan(new ByteArrayInputStream(new byte[] {1})))
          .isInstanceOfSatisfying(
              AwapException.class,
              ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.FILE_SCAN_FAILED));
    }
  }

  @Test
  void scannerNeverReadsMoreThanTheSmallBufferInOneCall() throws Exception {
    byte[] content = new byte[200_000];
    CapturingStream stream = new CapturingStream(content);
    try (FakeClamd clamd = new FakeClamd(false, "stream: OK")) {
      ClamdClamAvScanner scanner = new ClamdClamAvScanner("127.0.0.1", clamd.port(), 2_000);
      scanner.scan(stream);
    }

    assertThat(stream.bytesRead()).isEqualTo(content.length);
    assertThat(stream.maxRequestedBuffer()).isLessThanOrEqualTo(ClamAvStream.BUFFER_SIZE);
  }

  private static final class CapturingStream extends InputStream {
    private final byte[] content;
    private int position;
    private int maxRequestedBuffer;

    private CapturingStream(byte[] content) {
      this.content = content;
    }

    @Override
    public int read() {
      return position < content.length ? content[position++] & 0xff : -1;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      maxRequestedBuffer = Math.max(maxRequestedBuffer, length);
      if (position >= content.length) {
        return -1;
      }
      int read = Math.min(length, content.length - position);
      System.arraycopy(content, position, buffer, offset, read);
      position += read;
      return read;
    }

    private int bytesRead() {
      return position;
    }

    private int maxRequestedBuffer() {
      return maxRequestedBuffer;
    }
  }

  /** Minimal clamd server: parses INSTREAM and answers with a fixed response (or hangs). */
  private static final class FakeClamd implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final boolean hang;
    private final String response;

    private FakeClamd(boolean hang, String response) throws IOException {
      this.hang = hang;
      this.response = response;
      this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      executor.submit(this::serve);
    }

    private int port() {
      return server.getLocalPort();
    }

    private void serve() {
      try (Socket socket = server.accept()) {
        InputStream in = socket.getInputStream();
        readCommand(in);
        drainStreamBody(in);
        if (hang) {
          Thread.sleep(5_000);
          return;
        }
        socket.getOutputStream().write((response + "\0").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
      } catch (Exception ignored) {
        // Test server; assertions happen in the client thread.
      }
    }

    private void readCommand(InputStream in) throws IOException {
      int value;
      do {
        value = in.read();
      } while (value != 0 && value != -1);
    }

    private void drainStreamBody(InputStream in) throws IOException {
      ByteArrayOutputStream sink = new ByteArrayOutputStream();
      while (true) {
        int length = readInt(in);
        if (length == 0) {
          return;
        }
        sink.write(readExactly(in, length));
      }
    }

    private int readExactly(InputStream in, int length) throws IOException {
      int remaining = length;
      while (remaining > 0) {
        long skipped = in.skip(remaining);
        if (skipped <= 0) {
          if (in.read() == -1) {
            throw new IOException("Unexpected end of INSTREAM body");
          }
          skipped = 1;
        }
        remaining -= (int) skipped;
      }
      return length;
    }

    private int readInt(InputStream in) throws IOException {
      int b1 = in.read();
      int b2 = in.read();
      int b3 = in.read();
      int b4 = in.read();
      if ((b1 | b2 | b3 | b4) < 0) {
        throw new IOException("Unexpected end of INSTREAM length");
      }
      return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    @Override
    public void close() throws Exception {
      server.close();
      executor.shutdownNow();
    }
  }
}
