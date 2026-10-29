package com.awap.file.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Streams content through clamd's INSTREAM protocol using a small fixed buffer. No whole-file
 * buffering happens, so a 500 MB upload keeps the heap footprint bounded.
 */
public final class ClamAvStream {

  public static final int BUFFER_SIZE = 8 * 1024;

  private static final byte[] TERMINATOR = new byte[] {0, 0, 0, 0};

  private ClamAvStream() {}

  /**
   * Pumps {@code content} into {@code out} as length-prefixed INSTREAM chunks followed by the
   * zero-length terminator.
   *
   * @return number of content bytes streamed
   */
  public static long pump(InputStream content, OutputStream out) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0;
    int read;
    while ((read = content.read(buffer)) != -1) {
      if (read == 0) {
        continue;
      }
      out.write(chunkHeader(read));
      out.write(buffer, 0, read);
      total += read;
    }
    out.write(TERMINATOR);
    out.flush();
    return total;
  }

  private static byte[] chunkHeader(int length) {
    return new byte[] {
      (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length
    };
  }
}
