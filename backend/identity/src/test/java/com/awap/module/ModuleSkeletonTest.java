package com.awap.module;

import static org.assertj.core.api.Assertions.assertThat;

import com.awap.analytics.application.AnalyticsService;
import com.awap.analytics.domain.AnalyticsEventType;
import com.awap.document.application.DocumentService;
import com.awap.document.domain.DocumentStatus;
import com.awap.notification.application.NotificationService;
import com.awap.notification.domain.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

/** Verifies the ADR-003 module skeletons are wired as Spring services with stable domain enums. */
class ModuleSkeletonTest {

  @Test
  void notificationModuleIsWired() {
    assertThat(NotificationService.class.getAnnotation(Service.class)).isNotNull();
    assertThat(NotificationChannel.values())
        .containsExactly(
            NotificationChannel.EMAIL,
            NotificationChannel.SMS,
            NotificationChannel.PUSH,
            NotificationChannel.WEBHOOK);
  }

  @Test
  void analyticsModuleIsWired() {
    assertThat(AnalyticsService.class.getAnnotation(Service.class)).isNotNull();
    assertThat(AnalyticsEventType.values())
        .containsExactly(
            AnalyticsEventType.TASK_COMPLETED,
            AnalyticsEventType.WORKFLOW_COMPLETED,
            AnalyticsEventType.SLA_BREACHED);
  }

  @Test
  void documentModuleIsWired() {
    assertThat(DocumentService.class.getAnnotation(Service.class)).isNotNull();
    assertThat(DocumentStatus.values())
        .containsExactly(DocumentStatus.PENDING, DocumentStatus.AVAILABLE, DocumentStatus.ARCHIVED);
  }
}
