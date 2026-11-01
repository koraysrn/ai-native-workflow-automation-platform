package com.awap.file.infrastructure;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MinIO client configuration, fully driven by environment variables. */
@Configuration
public class MinioConfig {

  @Bean
  public MinioClient minioClient(
      @Value("${awap.minio.endpoint:http://localhost:9000}") String endpoint,
      @Value("${awap.minio.access-key:awapadmin}") String accessKey,
      @Value("${awap.minio.secret-key:awapadmin123}") String secretKey,
      @Value("${awap.minio.region:us-east-1}") String region) {
    // Pinning the region makes presigning a purely local operation. Without it the SDK performs a
    // GetBucketLocation round trip, which fails in the E2E stack where MinIO is an opt-in profile.
    return MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .region(region)
        .build();
  }
}
