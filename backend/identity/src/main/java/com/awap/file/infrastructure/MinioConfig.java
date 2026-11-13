package com.awap.file.infrastructure;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MinIO client configuration, fully driven by environment variables. */
@Configuration
public class MinioConfig {

  @Bean
  @Qualifier("minioClient")
  public MinioClient minioClient(
      @Value("${awap.minio.endpoint:http://localhost:9000}") String endpoint,
      @Value("${awap.minio.access-key:awapadmin}") String accessKey,
      @Value("${awap.minio.secret-key:awapadmin123}") String secretKey,
      @Value("${awap.minio.region:us-east-1}") String region) {
    return build(endpoint, accessKey, secretKey, region);
  }

  /**
   * Client used only to sign browser-facing URLs.
   *
   * <p>Inside Compose the backend talks to {@code http://minio:9000}, which a developer's browser
   * and the Playwright process cannot resolve. The public endpoint lets the backend keep using the
   * internal hostname for bucket and object operations while presigned PUT/GET URLs point at the
   * published port ({@code http://localhost:9000} by default). When it is blank the internal
   * endpoint is reused, so single-host deployments are unchanged.
   */
  @Bean
  @Qualifier("minioPresignClient")
  public MinioClient minioPresignClient(
      @Value("${awap.minio.public-endpoint:}") String publicEndpoint,
      @Value("${awap.minio.endpoint:http://localhost:9000}") String endpoint,
      @Value("${awap.minio.access-key:awapadmin}") String accessKey,
      @Value("${awap.minio.secret-key:awapadmin123}") String secretKey,
      @Value("${awap.minio.region:us-east-1}") String region) {
    String target = publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    return build(target, accessKey, secretKey, region);
  }

  private static MinioClient build(
      String endpoint, String accessKey, String secretKey, String region) {
    // Pinning the region makes presigning a purely local operation. Without it the SDK performs a
    // GetBucketLocation round trip, which fails when the storage service is not reachable from the
    // signing process (for example the E2E stack's public endpoint).
    return MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .region(region)
        .build();
  }
}
