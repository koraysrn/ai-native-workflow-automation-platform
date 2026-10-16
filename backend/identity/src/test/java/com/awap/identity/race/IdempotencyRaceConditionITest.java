package com.awap.identity.race;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.identity.domain.IdempotencyRecord;
import com.awap.identity.infrastructure.IdempotencyRecordRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/** Proves the DB unique constraint blocks one of two concurrent duplicate inserts. */
@SpringBootTest
class IdempotencyRaceConditionITest {

  @Autowired private IdempotencyRecordRepository repository;

  @Test
  void concurrentSameKeyOneMustFailWithUniqueConstraint() throws InterruptedException {
    int threadCount = 2;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch endGate = new CountDownLatch(threadCount);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger failure = new AtomicInteger();

    UUID tenantId = UUID.randomUUID();
    String key = "req_" + System.currentTimeMillis();

    for (int i = 0; i < threadCount; i++) {
      pool.submit(
          () -> {
            try {
              startGate.await();
              repository.saveAndFlush(new IdempotencyRecord(tenantId, key));
              success.incrementAndGet();
            } catch (DataIntegrityViolationException ex) {
              failure.incrementAndGet();
            } catch (Exception ex) {
              // unexpected infrastructure error
            } finally {
              endGate.countDown();
            }
          });
    }

    startGate.countDown();
    endGate.await();
    pool.shutdown();

    assertThat(success.get()).isEqualTo(1);
    assertThat(failure.get()).isEqualTo(1);
  }
}
