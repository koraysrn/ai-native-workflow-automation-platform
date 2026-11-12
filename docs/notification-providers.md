# Notification delivery transports (issue #204)

The e-mail, SMS and push adapters perform real provider calls behind
`NotificationChannelAdapter`. Each call is bounded, every failure is classified as permanent or
retryable, and the provider message id is stored on the delivery row.

## Configuration

Everything lives under `awap.notification.providers`. A transport is **disabled by default**, which
keeps the previous log-only behaviour for developer stacks without provider credentials. Enabling a
transport without its host/endpoint/token **fails fast at startup** — a misconfigured deployment can
never silently drop notifications.

| Setting | Env var | Default | Meaning |
|---|---|---|---|
| `providers.email.enabled` | `NOTIFICATION_EMAIL_ENABLED` | `false` | enable SMTP delivery |
| `providers.email.host` | `NOTIFICATION_SMTP_HOST` | — (required when enabled) | SMTP relay host |
| `providers.email.port` | `NOTIFICATION_SMTP_PORT` | `587` | SMTP relay port |
| `providers.email.username` | `NOTIFICATION_SMTP_USERNAME` | — | SMTP user (auth enabled when set) |
| `providers.email.password` | `NOTIFICATION_SMTP_PASSWORD` | — | SMTP password |
| `providers.email.from` | `NOTIFICATION_EMAIL_FROM` | `no-reply@awap.local` | envelope From |
| `providers.email.starttls` | `NOTIFICATION_SMTP_STARTTLS` | `true` | STARTTLS on the relay |
| `providers.email.timeout-ms` | `NOTIFICATION_SMTP_TIMEOUT_MS` | `5000` | connect + read + write timeout |
| `providers.sms.enabled` | `NOTIFICATION_SMS_ENABLED` | `false` | enable the SMS gateway |
| `providers.sms.endpoint` | `NOTIFICATION_SMS_ENDPOINT` | — (required) | gateway POST URL |
| `providers.sms.token` | `NOTIFICATION_SMS_TOKEN` | — (required) | bearer token |
| `providers.sms.sender` | `NOTIFICATION_SMS_SENDER` | `AWAP` | sender id |
| `providers.sms.timeout-ms` | `NOTIFICATION_SMS_TIMEOUT_MS` | `5000` | request timeout |
| `providers.push.enabled` | `NOTIFICATION_PUSH_ENABLED` | `false` | enable the push gateway |
| `providers.push.endpoint` | `NOTIFICATION_PUSH_ENDPOINT` | — (required) | FCM/APNs-compatible POST URL |
| `providers.push.token` | `NOTIFICATION_PUSH_TOKEN` | — (required) | bearer token |
| `providers.push.timeout-ms` | `NOTIFICATION_PUSH_TIMEOUT_MS` | `5000` | request timeout |

### Secrets

Never commit provider credentials. Supply them from the deployment secret store or Vault; the
application imports `optional:vault://` and binds the same keys:

```sh
vault kv put secret/awap \
  awap.notification.providers.email.username="$SMTP_USER" \
  awap.notification.providers.email.password="$SMTP_PASSWORD" \
  awap.notification.providers.sms.token="$SMS_TOKEN" \
  awap.notification.providers.push.token="$PUSH_TOKEN"
```

The adapters never log the token, the password or the rendered body. The SMS/push logs only carry the
tenant and the body length; the SMTP log only carries the recipient and the subject when the
transport is disabled. Rotation is covered by [secret-rotation.md](secret-rotation.md).

## Wire payloads

| Channel | Request | Provider id field |
|---|---|---|
| E-mail | SMTP `MAIL FROM`/`RCPT TO` + MIME body; a `Message-ID` header is generated and returned | `Message-ID` |
| SMS | `POST <endpoint>` with `Authorization: Bearer <token>` and `{"to","message","sender"}` | `id`, `message_id`, `messageId` |
| Push | `POST <endpoint>` with `Authorization: Bearer <token>` and `{"token","notification":{"title","body"}}` | `name`, `id`, `message_id`, `messageId` |

The provider id is stored in `notification_deliveries.provider_message_id` (V53) after a successful
dispatch, so support can join a delivery row with the provider's own bounce/complaint/receipt feed:

```sql
SET awap.tenant_id = '<tenant-uuid>';
SELECT id, channel, recipient, status, attempts, provider_message_id, created_at
FROM notification_deliveries
WHERE tenant_id = '<tenant-uuid>'
ORDER BY created_at DESC
LIMIT 20;
```

## Error classification

| Failure | Classification | Behaviour |
|---|---|---|
| SMTP `AuthenticationFailedException` | permanent (`SMTP_AUTH_FAILED`) | `FAILED`, dead-lettered, alert |
| SMTP 5xx recipient reply (`SMTPSendFailedException` ≥ 550) | permanent bounce (`SMTP_BOUNCE`) | `FAILED` + recipient suppressed |
| SMTP 4xx reply | retryable (`SMTP_TEMPORARY`) | retry scheduler |
| SMTP invalid address | permanent (`SMTP_ADDRESS_INVALID`) | `FAILED` |
| SMTP IO / connect / read timeout | retryable (`SMTP_IO`) | retry scheduler |
| HTTP 4xx (except 408/429) | permanent (`*_REJECTED`) | `FAILED` |
| HTTP 4xx mentioning bounce/complaint/suppressed/unsubscribed | permanent bounce (`*_BOUNCE`) | `FAILED` + recipient suppressed |
| HTTP 408 / 429 | retryable (`*_RETRYABLE`) | retry scheduler |
| HTTP 5xx | retryable (`*_UNAVAILABLE`) | retry scheduler |
| HTTP 1xx/3xx | permanent (`*_UNEXPECTED_STATUS`) | `FAILED` |
| Timeout / connection reset / IO | retryable (`*_IO`) | retry scheduler |

Retryable failures are handed to `NotificationRetryWorker`/`NotificationRetryScheduler` (see runbook
O11); the consumer never sleeps and the first attempt is bounded by the configured timeout.

## Recipient validation and tenant ownership

`NotificationRecipients.validate` runs **before** the provider call:

- the message must carry a tenant id (every delivery is built from a tenant-scoped row);
- `EMAIL` must look like `local@domain.tld`, `SMS` must be E.164 (`+905551234567`), `PUSH` must be a
  device token of at least 16 URL-safe characters;
- `WEB` and `WEBHOOK` accept the stored recipient.

A rejected recipient becomes `FAILED` immediately with no provider call and is dead-lettered, so a
typo cannot burn the retry budget.

## Bounce and complaint suppression

A permanent bounce or complaint inserts the recipient into `notification_suppressions` (V54,
tenant-scoped, RLS-protected). Later deliveries to the same `(tenant, channel, recipient)` are marked
`FAILED` with `RECIPIENT_SUPPRESSED` and never reach the provider. The suppression counter is
`awap.notification.suppressed{channel,reason}`; the delivery is not retried and the suppression list
is only appended (delete a row deliberately after a verified fix/re-consent).

## Tests

- `EmailChannelAdapterITest` starts GreenMail and asserts the rendered subject, body, recipient and
  `Message-ID` that the adapter returned.
- `HttpProviderAdapterTest` runs a JDK HTTP fake provider and asserts the SMS/push JSON payload, the
  bearer header, the recorded id, and every classification branch (4xx, 429, 5xx, 302, timeout,
  connection refused, bounce/complaint bodies).
- `EmailChannelAdapterTest` covers the fail-fast configuration checks and the SMTP classification
  (auth, 5xx bounce, 4xx retry, address failure, IO timeout).
- `NotificationServiceTest` / `NotificationRetryWorkerTest` prove the recipient validation, the
  suppression pre-check, the bounce recording and the persisted provider id.

Run them with:

```sh
cd backend
mvn -B -pl identity -am test -Dtest='EmailChannelAdapter*,HttpProviderAdapterTest,Notification*Test'
```

## Rollback

Disable a transport (`NOTIFICATION_*_ENABLED=false`) to fall back to the log-only adapter without a
redeploy of code; the delivery rows keep their provider ids for audit. Reverting V53/V54 is
destructive (it drops the provider id and the suppression list) and only allowed after the release
that introduced them is fully rolled back.
