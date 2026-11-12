package com.awap.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transport configuration for the real e-mail, SMS and push providers (issue #204).
 *
 * <pre>
 * awap:
 *   notification:
 *     providers:
 *       email:
 *         enabled: true
 *         host: smtp.example.com
 *         port: 587
 *         username: ${NOTIFICATION_SMTP_USERNAME}
 *         password: ${NOTIFICATION_SMTP_PASSWORD}
 *         from: no-reply@example.com
 *         starttls: true
 *         timeout-ms: 5000
 *       sms:
 *         enabled: true
 *         endpoint: https://sms.example.com/v1/messages
 *         token: ${NOTIFICATION_SMS_TOKEN}
 *         sender: AWAP
 *         timeout-ms: 5000
 *       push:
 *         enabled: true
 *         endpoint: https://fcm.example.com/v1/messages:send
 *         token: ${NOTIFICATION_PUSH_TOKEN}
 *         timeout-ms: 5000
 * </pre>
 *
 * <p>Every credential comes from an environment variable or Vault; nothing here is ever logged. A
 * transport that is disabled keeps the previous log-only behaviour so a developer stack does not
 * need provider credentials, while an <em>enabled</em> transport with a missing host, endpoint or
 * token fails fast at startup instead of silently dropping notifications.
 */
@ConfigurationProperties(prefix = "awap.notification.providers")
public class NotificationProviderProperties {

  private Email email = new Email();

  private Sms sms = new Sms();

  private Push push = new Push();

  public Email getEmail() {
    return email;
  }

  public void setEmail(Email email) {
    this.email = email == null ? new Email() : email;
  }

  public Sms getSms() {
    return sms;
  }

  public void setSms(Sms sms) {
    this.sms = sms == null ? new Sms() : sms;
  }

  public Push getPush() {
    return push;
  }

  public void setPush(Push push) {
    this.push = push == null ? new Push() : push;
  }

  /** SMTP settings used by {@link EmailChannelAdapter}. */
  public static class Email {

    private boolean enabled;

    private String host;

    private int port = 587;

    private String username;

    private String password;

    private String from = "no-reply@awap.local";

    private boolean starttls = true;

    /** Bound for connect, read and write on the first (in-consumer) attempt. */
    private long timeoutMs = 5_000L;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public int getPort() {
      return port;
    }

    public void setPort(int port) {
      this.port = port;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public String getFrom() {
      return from;
    }

    public void setFrom(String from) {
      this.from = from;
    }

    public boolean isStarttls() {
      return starttls;
    }

    public void setStarttls(boolean starttls) {
      this.starttls = starttls;
    }

    public long getTimeoutMs() {
      return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
      this.timeoutMs = timeoutMs;
    }
  }

  /** HTTP SMS gateway settings used by {@link SmsChannelAdapter}. */
  public static class Sms {

    private boolean enabled;

    private String endpoint;

    /** Bearer token; supplied from env/Vault and never logged. */
    private String token;

    private String sender = "AWAP";

    private long timeoutMs = 5_000L;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
    }

    public String getToken() {
      return token;
    }

    public void setToken(String token) {
      this.token = token;
    }

    public String getSender() {
      return sender;
    }

    public void setSender(String sender) {
      this.sender = sender;
    }

    public long getTimeoutMs() {
      return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
      this.timeoutMs = timeoutMs;
    }
  }

  /** FCM-compatible push settings used by {@link PushChannelAdapter}. */
  public static class Push {

    private boolean enabled;

    private String endpoint;

    /** Bearer token; supplied from env/Vault and never logged. */
    private String token;

    private long timeoutMs = 5_000L;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
    }

    public String getToken() {
      return token;
    }

    public void setToken(String token) {
      this.token = token;
    }

    public long getTimeoutMs() {
      return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
      this.timeoutMs = timeoutMs;
    }
  }
}
