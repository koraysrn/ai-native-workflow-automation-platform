# Form FILE field submission contract (issue #200)

A FILE form field never accepts an arbitrary string. It stores the id of a
tenant-owned file that the platform has scanned and promoted.

## Payload

The submission `dataJson` maps each FILE field id to one of:

```json
{ "contract": "550e8400-e29b-41d4-a716-446655440000" }
```

```json
{ "attachments": ["550e8400-e29b-41d4-a716-446655440000", "6ba7b810-9dad-11d1-80b4-00c04fd430c8"] }
```

`fileId` (a single string) and `fileIds` (an array) are equivalent. Duplicate ids
are deduplicated. A blank value, a non-UUID value or an empty array fails with
`400`.

## Server validation

`FormService` resolves every id through the file module before the submission row
is written:

| Condition | Status | Error code |
|-----------|--------|------------|
| Missing or owned by another tenant | `404` | `AWAP-FILE-0001` |
| Infected (`INFECTED`) | `403` | `AWAP-FILE-0002` |
| Not promoted yet (`QUARANTINED`, `SCANNING`, `CLEAN`, `SCAN_FAILED`) | `409` | `AWAP-FILE-0006` |
| Unsupported extension at read time | `415` | `AWAP-FILE-0004` |
| Larger than 500 MB or empty at read time | `413` / `400` | `AWAP-FILE-0003` / `AWAP-FILE-0005` |

Only `PROMOTED` files are accepted, so a pending or infected file can never be
attached to a form response.

## Response

The submission response echoes the accepted metadata:

```json
{
  "id": "…",
  "files": [
    {
      "fileId": "550e8400-e29b-41d4-a716-446655440000",
      "name": "contract.pdf",
      "size": 2048,
      "contentType": "application/pdf",
      "scanStatus": "PROMOTED"
    }
  ]
}
```

## Audit and analytics

Every submission with accepted files publishes a `FORM_FILES_SUBMITTED` event
through the transactional outbox with the submission id, form id and the file
metadata. Consumers get the audit trail without reading file contents.

## Upload UI

[`FormFileField`](../frontend/components/forms/FormFileField.tsx) creates the
quarantine slot, PUTs the bytes with progress, polls the scan status and stores
only the returned file id in the form value. Submitting stays enabled while the
scan is pending; the server returns `409` and the response includes the promoted
status once the file is ready.
