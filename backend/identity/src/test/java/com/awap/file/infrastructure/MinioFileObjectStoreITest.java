package com.awap.file.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.BucketExistsArgs;
import io.minio.GetBucketLifecycleArgs;
import io.minio.GetBucketVersioningArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectLockConfigurationArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.ObjectLockConfiguration;
import io.minio.messages.RetentionMode;
import io.minio.messages.VersioningConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real MinIO proof for versioning/object-lock/lifecycle, server-side copy and presign TTL. */
@Testcontainers(disabledWithoutDocker = true)
class MinioFileObjectStoreITest {

  private static final String MINIO_IMAGE = "quay.io/minio/minio:latest";

  static final GenericContainer<?> MINIO =
      new GenericContainer<>(MINIO_IMAGE)
          .withCommand("server /data")
          .withEnv("MINIO_ROOT_USER", "minioadmin")
          .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
          .withExposedPorts(9000)
          .waitingFor(
              Wait.forHttp("/minio/health/live")
                  .forPort(9000)
                  .withStartupTimeout(Duration.ofSeconds(90)));

  private static MinioClient client;
  private static MinioFileObjectStore store;

  @BeforeAll
  static void setUp() {
    // The fake adapter covers the same branches in CI; run the real MinIO proof whenever the
    // image is available locally (this sandbox cannot reach the MinIO registry).
    Assumptions.assumeTrue(
        imageAvailable(), "MinIO image not available locally; relying on the fake adapter tests");
    MINIO.start();
    client =
        MinioClient.builder()
            .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
            .credentials("minioadmin", "minioadmin")
            .build();
    store = new MinioFileObjectStore(client);
  }

  @AfterAll
  static void tearDown() {
    if (MINIO.isRunning()) {
      MINIO.stop();
    }
  }

  private static boolean imageAvailable() {
    try {
      DockerClientFactory.instance().client().inspectImageCmd(MINIO_IMAGE).exec();
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  @Test
  void quarantineBucketIsVersionedObjectLockedAndLifecycleManaged() throws Exception {
    String bucket = "awap-quarantine-" + UUID.randomUUID();

    store.ensureBucket(bucket, true, 1);

    assertThat(client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())).isTrue();
    VersioningConfiguration versioning =
        client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build());
    assertThat(versioning.status()).isEqualTo(VersioningConfiguration.Status.ENABLED);

    ObjectLockConfiguration lock =
        client.getObjectLockConfiguration(
            GetObjectLockConfigurationArgs.builder().bucket(bucket).build());
    assertThat(lock.mode()).isEqualTo(RetentionMode.GOVERNANCE);

    LifecycleConfiguration lifecycle =
        client.getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(bucket).build());
    assertThat(lifecycle.rules()).isNotEmpty();
  }

  @Test
  void serverSideCopyPromotesAndDeleteRemovesTheQuarantineSource() throws Exception {
    String quarantine = "awap-quarantine-" + UUID.randomUUID();
    String promoted = "awap-files-" + UUID.randomUUID();
    store.ensureBucket(quarantine, true, 1);
    store.ensureBucket(promoted, false, 1);
    String key = "tenant/file/doc.txt";

    client.putObject(
        PutObjectArgs.builder().bucket(quarantine).object(key).stream(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                "hello".length(),
                -1)
            .build());

    store.copy(quarantine, key, promoted, key);

    try (var content =
        client.getObject(GetObjectArgs.builder().bucket(promoted).object(key).build())) {
      assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    store.delete(quarantine, key);
    assertThat(store.exists(quarantine, key)).isFalse();
    assertThat(store.exists(promoted, key)).isTrue();
  }

  @Test
  void presignedPutTtlIsAtMostFiveMinutes() throws Exception {
    String bucket = "awap-quarantine-" + UUID.randomUUID();
    store.ensureBucket(bucket, true, 1);

    String url = store.presignedPut(bucket, "tenant/file/doc.txt", Duration.ofMinutes(5));

    assertThat(url).contains("X-Amz-Expires=300");
  }

  @Test
  void existingBucketWithoutObjectLockStillGetsVersioningAndLifecycle() throws Exception {
    String bucket = "awap-files-" + UUID.randomUUID();
    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());

    store.ensureBucket(bucket, true, 2);

    assertThat(
            client
                .getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build())
                .status())
        .isEqualTo(VersioningConfiguration.Status.ENABLED);
    assertThat(
            client
                .getBucketLifecycle(GetBucketLifecycleArgs.builder().bucket(bucket).build())
                .rules())
        .isNotEmpty();
  }
}
