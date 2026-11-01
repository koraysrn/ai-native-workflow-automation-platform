package com.awap.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Ensures presigning stays a local operation when the configured region is pinned. */
class MinioConfigTest {

  @Test
  void presigningSucceedsWithoutAReachableServerWhenTheRegionIsPinned() throws Exception {
    MinioClient client =
        new MinioConfig().minioClient("http://127.0.0.1:1", "access", "secret", "us-east-1");

    String url =
        client.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket("awap-quarantine")
                .object("tenant/file.pdf")
                .expiry(60, TimeUnit.SECONDS)
                .build());

    assertThat(url).contains("127.0.0.1:1").contains("awap-quarantine").contains("X-Amz-Signature");
  }
}
