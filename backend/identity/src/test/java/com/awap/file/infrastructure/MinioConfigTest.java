package com.awap.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Ensures presigning stays a local operation when the configured region is pinned. */
class MinioConfigTest {

  private static String presign(MinioClient client) throws Exception {
    return client.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
            .method(Method.PUT)
            .bucket("awap-quarantine")
            .object("tenant/file.pdf")
            .expiry(60, TimeUnit.SECONDS)
            .build());
  }

  @Test
  void presigningSucceedsWithoutAReachableServerWhenTheRegionIsPinned() throws Exception {
    MinioClient client =
        new MinioConfig().minioClient("http://127.0.0.1:1", "access", "secret", "us-east-1");

    String url = presign(client);

    assertThat(url).contains("127.0.0.1:1").contains("awap-quarantine").contains("X-Amz-Signature");
  }

  @Test
  void thePresignClientUsesThePublicEndpointWhenConfigured() throws Exception {
    MinioClient client =
        new MinioConfig()
            .minioPresignClient(
                "http://localhost:9000", "http://minio:9000", "access", "secret", "us-east-1");

    assertThat(presign(client)).contains("http://localhost:9000");
  }

  @Test
  void aBlankPublicEndpointFallsBackToTheInternalEndpoint() throws Exception {
    MinioClient client =
        new MinioConfig()
            .minioPresignClient("", "http://minio:9000", "access", "secret", "us-east-1");

    assertThat(presign(client)).contains("http://minio:9000");
  }

  @Test
  void theObjectStorePresignsWithThePublicClient() {
    MinioConfig config = new MinioConfig();
    MinioClient internal =
        config.minioClient("http://127.0.0.1:1", "access", "secret", "us-east-1");
    MinioClient presign =
        config.minioPresignClient(
            "http://localhost:9000", "http://minio:9000", "access", "secret", "us-east-1");
    MinioFileObjectStore store = new MinioFileObjectStore(internal, presign);

    assertThat(store.presignedPut("awap-quarantine", "tenant/file.pdf", Duration.ofSeconds(60)))
        .contains("http://localhost:9000");
    assertThat(store.presignedGet("awap-files", "tenant/file.pdf", Duration.ofSeconds(60)))
        .contains("http://localhost:9000");
  }
}
