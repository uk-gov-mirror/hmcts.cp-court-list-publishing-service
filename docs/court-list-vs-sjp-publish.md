# CourtListPublish vs SJP Publish

Comparison of the two async publish flows in this service: the standard/online-public
court list flow (`CourtListPublish`) and the Single Justice Procedure flow (`SjpPublish`).

## Request payloads

### CourtListPublish — `POST /api/court-list-publish/publish`

Only identifies *what* to publish; the actual case/hearing data is fetched later by the
async task from `CourtListQueryService` (progression-service).

```json
{
    "courtCentreId": "b5b3c6b0-1234-4a5a-9c3e-1a2b3c4d5e6f",
    "startDate": "2026-01-20",
    "endDate": "2026-01-20",
    "courtListType": "ONLINE_PUBLIC"
}
```

Response — `CourtListPublishResponse` (immediate, `publishStatus` = `REQUESTED`):

```json
{
    "courtListId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "courtCentreId": "b5b3c6b0-1234-4a5a-9c3e-1a2b3c4d5e6f",
    "publishStatus": "REQUESTED",
    "fileStatus": "REQUESTED",
    "courtListType": "ONLINE_PUBLIC",
    "lastUpdated": "2026-01-20T09:00:00Z",
    "publishDate": "2026-01-20"
}
```

Real outcome (`SUCCESSFUL`/`FAILED`) is polled afterwards via
`GET /api/court-list-publish/publish-status?courtListId=...`.

### SJP Publish — `POST /api/court-list-publish/sjp/publishCourtList`

The full case payload is included directly in the request — no separate query step.

```json
{
  "listType": "SJP_PUBLIC_LIST",
  "requestType": "FULL",
  "listPayload": {
    "generatedDateAndTime": "2025-06-01T09:00:00",
    "courtIdNumeric": "325",
    "isWelsh": false,
    "readyCases": [
      {
        "caseUrn": "URN-PUBLIC-001",
        "defendantName": "Jane Smith",
        "firstName": "J",
        "lastName": "Smith",
        "dateOfBirth": "1990-01-15",
        "addressLine1": "10 Main Street",
        "town": "Manchester",
        "postcode": "M1",
        "prosecutorName": "Crown Prosecution Service",
        "sjpOffences": [
          { "title": "Speeding", "wording": "Drove at 50mph in a 30mph zone" }
        ]
      }
    ]
  }
}
```

Response — `PublishCourtListResponse` (immediate; `status` means "accepted for async
processing", not a confirmed CaTH outcome — there's no id here to poll with):

```json
{
    "status": "ACCEPTED",
    "listType": "SJP_PUBLIC_LIST",
    "message": "SJP court list publish request accepted for processing"
}
```

## Side-by-side differences

| | **CourtListPublish** | **SJP Publish** |
|---|---|---|
| Endpoint | `POST /api/court-list-publish/publish` | `POST /api/court-list-publish/sjp/publishCourtList` |
| Request payload | `courtCentreId` + dates + `courtListType` only | Full `listPayload` (case data included) |
| Data source for content | Async task queries progression-service later | Already in the request; task only transforms it |
| PDF generation | Yes — `CourtListPdfHelper`, tracked by `fileStatus`/`fileId`/`fileUrl` | None — CaTH publish only |
| DB table | `court_list_publish_status` | `sjp_publish_status` |
| Dedup key | `courtCentreId` (UUID), `publishDate`, `courtListType` (enum) | `courtIdNumeric` (string), `listType` (string), `publishDate` |
| Content-hash dedup | None — always re-uploads/re-publishes | Yes — `payloadHash` skips re-upload/re-publish of identical content |
| Async task trigger | `CourtListTaskTriggerService` → `PUBLISH_AND_PDF_GENERATION_TASK` | `SjpTaskTriggerService` → `SJP_PUBLISH_TASK` |
| Async task worker | `CourtListPublishAndPDFGenerationTask` | `SjpPublishTask` |
| Status polling | `GET /api/court-list-publish/publish-status?courtListId=...` | None — no id in the response to poll with |
| Blob name | `<courtListId>-cath.json` | `<sjpListId>-sjp-cath.json` |
| CaTH list types | `MAGISTRATES_PUBLIC_LIST` / `MAGISTRATES_STANDARD_LIST` | `SJP_PUBLIC_LIST` / `SJP_PRESS_LIST` |
| JSON schema | `standard-court-list-schema.json` / `online-public-court-list-schema.json` | `single-justice-procedure-public.json` / `single-justice-procedure-press.json` |

## Why they can't share the same DB table

`court_list_publish_status.court_centre_id` is a `UUID NOT NULL`, and `court_list_type` is
the `CourtListType` enum generated from the external `api-cp-crime-court-list-publisher`
contract jar (`ONLINE_PUBLIC`/`STANDARD`/`PRISON`/etc). SJP's identifiers don't fit that
shape: `courtIdNumeric` is a numeric-string reference-data id (e.g. `"325"`, defaulting to
`"0"`), and its list types (`SJP_PUBLIC_LIST`/`SJP_PRESS_LIST`) aren't part of that enum.
Reusing the table would mean hashing the numeric string into a synthetic UUID (a column
that sometimes holds a real court centre id and sometimes a fake one) or adding SJP values
to an enum owned by a separate contract module — plus a migration would still be needed
for `payload_hash` either way. `sjp_publish_status` (added in
`V1003__create_sjp_publish_status_table.sql`) keeps the two domains distinct instead.
