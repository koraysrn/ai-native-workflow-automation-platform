package com.awap.file.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;

/** Proves the 500 MB scan path streams with a bounded heap instead of buffering the file. */
class ClamAvStreamHeapTest {

  private static final long MAX_FILE_BYTES = 500L * 1024 * 1024;
  private static final long HEAP_LIMIT_BYTES = 64L * 1024 * 1024;

  @Test
  void fiveHundredMegabyteStreamKeepsHeapBounded() throws Exception {
    System.gc();
    long before = usedHeap();
    SyntheticStream content = new SyntheticStream(MAX_FILE_BYTES);
    CountingOutputStream sink = new CountingOutputStream();

    long streamed = ClamAvStream.pump(content, sink);

    System.gc();
    long after = usedHeap();

    assertThat(streamed).isEqualTo(MAX_FILE_BYTES);
    assertThat(content.maxRequestedBuffer()).isLessThanOrEqualTo(ClamAvStream.BUFFER_SIZE);
    assertThat(sink.bytes()).isGreaterThan(MAX_FILE_BYTES);
    assertThat(after - before).isLessThan(HEAP_LIMIT_BYTES);
  }

  private long usedHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static final class SyntheticStream extends InputStream {
    private final long total;
    private long produced;
    private int maxRequestedBuffer;

    private SyntheticStream(long total) {
      this.total = total;
    }

    @Override
    public int read() {
      if (produced >= total) {
        return -1;
      }
      produced++;
      return 0;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      maxRequestedBuffer = Math.max(maxRequestedBuffer, length);
      if (produced >= total) {
        return -1;
      }
      int read = (int) Math.min(length, total - produced);
      for (int i = 0; i < read; i++) {
        buffer[offset + i] = 0;
      }
      produced += read;
      return read;
    }

    private int maxRequestedBuffer() {
      return maxRequestedBuffer;
    }
  }

  private static final class CountingOutputStream extends OutputStream {
    private long bytes;

    @Override
    public void write(int value) {
      bytes++;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
      bytes += length;
    }

    private long bytes() {
      return bytes;
    }
  }
}
