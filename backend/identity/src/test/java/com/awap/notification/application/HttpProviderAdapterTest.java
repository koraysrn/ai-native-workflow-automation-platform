package com.awap.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awap.notification.domain.NotificationChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wire-level tests for the SMS and push transports against a fake provider server (issue #204).
 * Every assertion inspects the JSON the adapter actually posted.
 */
class HttpProviderAdapterTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final String TOKEN = "provider-token";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicReference<String> lastContentType = new AtomicReference<>();
  private volatile int responseStatus = 202;
  private volatile String responseBody = "{\"id\":\"msg-123\"}";
  private volatile long delayMs;
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/messages",
        exchange -> {
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          if (delayMs > 0) {
            sleep(delayMs);
          }
          byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(responseStatus, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/messages";
  }

  private NotificationProviderProperties.Sms smsSettings() {
    NotificationProviderProperties.Sms settings = new NotificationProviderProperties.Sms();
    settings.setEnabled(true);
    settings.setEndpoint(endpoint());
    settings.setToken(TOKEN);
    settings.setSender("EGS");
    settings.setTimeoutMs(2_000L);
    return settings;
  }

  private NotificationProviderProperties.Push pushSettings() {
    NotificationProviderProperties.Push settings = new NotificationProviderProperties.Push();
    settings.setEnabled(true);
    settings.setEndpoint(endpoint());
    settings.setToken(TOKEN);
    settings.setTimeoutMs(2_000L);
    return settings;
  }

  private SmsChannelAdapter sms(NotificationProviderProperties.Sms settings) {
    return new SmsChannelAdapter(settings, objectMapper, HttpClient.newHttpClient());
  }

  private PushChannelAdapter push(NotificationProviderProperties.Push settings) {
    return new PushChannelAdapter(settings, objectMapper, HttpClient.newHttpClient());
  }

  private static NotificationMessage smsMessage() {
    return NotificationMessage.of(
        TENANT, "+905551234567", NotificationChannel.SMS, "Subject", "Ada");
  }

  private static NotificationMessage pushMessage() {
    return NotificationMessage.of(
        TENANT, "cJk3m9-abcdefghijklmnop_1234567890", NotificationChannel.PUSH, "Subject", "Ada");
  }

  @Test
  void smsPostsTheExpectedPayloadAndReturnsTheProviderId() throws Exception {
    String providerMessageId = sms(smsSettings()).dispatch(smsMessage());

    assertThat(providerMessageId).isEqualTo("msg-123");
    JsonNode payload = objectMapper.readTree(lastBody.get());
    assertThat(payload.get("to").asText()).isEqualTo("+905551234567");
    assertThat(payload.get("message").asText()).isEqualTo("Ada");
    assertThat(payload.get("sender").asText()).isEqualTo("EGS");
    assertThat(lastAuthorization.get()).isEqualTo("Bearer " + TOKEN);
    assertThat(lastContentType.get()).isEqualTo("application/json");
  }

  @Test
  void pushPostsTheExpectedPayloadAndReturnsTheProviderId() throws Exception {
    responseBody = "{\"name\":\"projects/awap/messages/42\"}";

    String providerMessageId = push(pushSettings()).dispatch(pushMessage());

    assertThat(providerMessageId).isEqualTo("projects/awap/messages/42");
    JsonNode payload = objectMapper.readTree(lastBody.get());
    assertThat(payload.get("token").asText()).isEqualTo("cJk3m9-abcdefghijklmnop_1234567890");
    assertThat(payload.get("notification").get("title").asText()).isEqualTo("Subject");
    assertThat(payload.get("notification").get("body").asText()).isEqualTo("Ada");
    assertThat(lastAuthorization.get()).isEqualTo("Bearer " + TOKEN);
  }

  @Test
  void anAlternateIdFieldIsAccepted() {
    responseBody = "{\"message_id\":\"alt-1\"}";

    assertThat(sms(smsSettings()).dispatch(smsMessage())).isEqualTo("alt-1");
  }

  @Test
  void aTwoHundredResponseWithoutAnIdReturnsNull() {
    responseBody = "{\"status\":\"queued\"}";

    assertThat(sms(smsSettings()).dispatch(smsMessage())).isNull();
  }

  @Test
  void anEmptyOrNonJsonSuccessReturnsNull() {
    responseBody = "";

    assertThat(sms(smsSettings()).dispatch(smsMessage())).isNull();
    responseBody = "queued";
    assertThat(sms(smsSettings()).dispatch(smsMessage())).isNull();
  }

  @Test
  void aNullIdValueIsIgnored() {
    responseBody = "{\"id\":null}";

    assertThat(sms(smsSettings()).dispatch(smsMessage())).isNull();
  }

  @Test
  void aFourHundredIsPermanent() {
    responseStatus = 400;

    assertThatThrownBy(() -> sms(smsSettings()).dispatch(smsMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMS_REJECTED");
              assertThat(ex.isPermanent()).isTrue();
            });
  }

  @Test
  void aComplaintResponseBlocksTheRecipient() {
    responseStatus = 410;
    responseBody = "{\"error\":\"recipient complaint on file\"}";

    assertThatThrownBy(() -> push(pushSettings()).dispatch(pushMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.isRecipientBlocked()).isTrue();
              assertThat(ex.getReason()).isEqualTo("PUSH_BOUNCE");
            });
  }

  @Test
  void aBounceResponseBlocksTheRecipient() {
    responseStatus = 422;
    responseBody = "hard bounce";

    assertThatThrownBy(() -> sms(smsSettings()).dispatch(smsMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> assertThat(ex.isRecipientBlocked()).isTrue());
  }

  @Test
  void anUnsubscribedResponseBlocksTheRecipient() {
    responseStatus = 403;
    responseBody = "unsubscribed";

    assertThatThrownBy(() -> sms(smsSettings()).dispatch(smsMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> assertThat(ex.isRecipientBlocked()).isTrue());
  }

  @Test
  void aNullErrorBodyIsNotABounce() {
    responseStatus = 404;
    responseBody = "";

    assertThatThrownBy(() -> sms(smsSettings()).dispatch(smsMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> assertThat(ex.isRecipientBlocked()).isFalse());
  }

  @Test
  void throttlingStaysRetryable() {
    responseStatus = 429;

    assertThatThrownBy(() -> sms(smsSettings()).dispatch(smsMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMS_RETRYABLE");
              assertThat(ex.isPermanent()).isFalse();
            });
  }

  @Test
  void aRequestTimeoutStatusStaysRetryable() {
    responseStatus = 408;

    assertThatThrownBy(() -> push(pushSettings()).dispatch(pushMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class, ex -> assertThat(ex.isPermanent()).isFalse());
  }

  @Test
  void aServerErrorStaysRetryable() {
    responseStatus = 503;

    assertThatThrownBy(() -> push(pushSettings()).dispatch(pushMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("PUSH_UNAVAILABLE");
              assertThat(ex.isPermanent()).isFalse();
            });
  }

  @Test
  void anUnexpectedSuccessClassStatusIsPermanent() {
    responseStatus = 302;

    assertThatThrownBy(() -> sms(smsSettings()).dispatch(smsMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMS_UNEXPECTED_STATUS");
              assertThat(ex.isPermanent()).isTrue();
            });
  }

  @Test
  void aReadTimeoutStaysRetryable() {
    delayMs = 500;
    NotificationProviderProperties.Sms settings = smsSettings();
    settings.setTimeoutMs(100L);

    assertThatThrownBy(() -> sms(settings).dispatch(smsMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("SMS_IO");
              assertThat(ex.isPermanent()).isFalse();
            });
  }

  @Test
  void aConnectionFailureStaysRetryable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    NotificationProviderProperties.Push settings = pushSettings();
    settings.setEndpoint("http://127.0.0.1:" + closedPort + "/messages");
    settings.setTimeoutMs(500L);

    assertThatThrownBy(() -> push(settings).dispatch(pushMessage()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            ex -> {
              assertThat(ex.getReason()).isEqualTo("PUSH_IO");
              assertThat(ex.isPermanent()).isFalse();
            });
  }

  @Test
  void aDisabledTransportSkipsTheProviderCall() {
    NotificationProviderProperties.Sms settings = smsSettings();
    settings.setEnabled(false);

    assertThat(sms(settings).dispatch(smsMessage())).isNull();
    assertThat(lastBody.get()).isNull();
  }

  @Test
  void enabledWithoutEndpointOrTokenFailsFast() {
    NotificationProviderProperties.Sms noEndpoint = smsSettings();
    noEndpoint.setEndpoint(" ");

    assertThatThrownBy(() -> sms(noEndpoint))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint/token");

    NotificationProviderProperties.Push noToken = pushSettings();
    noToken.setToken(null);

    assertThatThrownBy(() -> push(noToken))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint/token");
  }

  @Test
  void anInvalidEndpointUriFailsFast() {
    NotificationProviderProperties.Push settings = pushSettings();
    settings.setEndpoint("http://bad host/messages");

    assertThatThrownBy(() -> push(settings))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a valid URI");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
