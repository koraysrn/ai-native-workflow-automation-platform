package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.awap.notification.domain.NotificationChannel;
import com.awap.webhook.infrastructure.WebhookClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Dispatch behaviour of the in-app and webhook adapters (issues #204 and #168). */
class ChannelAdapterDispatchTest {

  private static final UUID TENANT = UUID.randomUUID();

  @Test
  void theWebAdapterAcknowledgesTheInAppDeliveryWithoutAProviderId() {
    WebChannelAdapter adapter = new WebChannelAdapter();

    String providerMessageId =
        adapter.dispatch(
            NotificationMessage.of(TENANT, "user-1", NotificationChannel.WEB, "Hi", "Hello Ada"));

    assertThat(providerMessageId).isNull();
    assertThat(adapter.channel()).isEqualTo(NotificationChannel.WEB);
  }

  @Test
  void theWebhookAdapterPostsThePayloadAndHasNoProviderId() {
    WebhookClient client = mock(WebhookClient.class);
    WebhookChannelAdapter adapter = new WebhookChannelAdapter(client, new ObjectMapper());

    String providerMessageId =
        adapter.dispatch(
            new NotificationMessage(
                TENANT,
                "endpoint-1",
                NotificationChannel.WEBHOOK,
                "Hi",
                "Hello Ada",
                "https://hooks.example.com/awap"));

    assertThat(providerMessageId).isNull();
    verify(client)
        .post(org.mockito.ArgumentMatchers.eq("https://hooks.example.com/awap"), anyString());
  }

  @Test
  void theWebhookAdapterRejectsAMissingTargetBeforeAnyCall() {
    WebhookClient client = mock(WebhookClient.class);
    WebhookChannelAdapter adapter = new WebhookChannelAdapter(client, new ObjectMapper());
    NotificationMessage message =
        NotificationMessage.of(TENANT, "endpoint-1", NotificationChannel.WEBHOOK, "Hi", "Hello");

    assertThatThrownBy(() -> adapter.dispatch(message))
        .isInstanceOf(com.awap.common.api.AwapException.class)
        .hasMessageContaining("target URL");
    verifyNoInteractions(client);
  }

  @Test
  void aWebhookTransportFailurePropagatesAsARetryableRuntimeException() {
    WebhookClient client = mock(WebhookClient.class);
    doThrow(new IllegalStateException("connection reset"))
        .when(client)
        .post(anyString(), anyString());
    WebhookChannelAdapter adapter = new WebhookChannelAdapter(client, new ObjectMapper());

    assertThatThrownBy(
            () ->
                adapter.dispatch(
                    new NotificationMessage(
                        TENANT,
                        "endpoint-1",
                        NotificationChannel.WEBHOOK,
                        "Hi",
                        "Hello Ada",
                        "https://hooks.example.com/awap")))
        .isInstanceOf(IllegalStateException.class);
  }
}
