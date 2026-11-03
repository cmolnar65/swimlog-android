# Health Connect import data contract

**Status:** Proposed v1 contract for the Android app and its Swim Log receiver. This document does not implement or imply an existing endpoint. The current WordPress REST API is read-only; the receiving endpoint, Health Connect provider support, and any token issuance flow remain separate prerequisites.

## Agreed v1 scope

- One-way flow: Health Connect → Android app → the authenticated owner's private Swim Log account.
- Activity types: pool swimming, open-water swimming, running (including treadmill), and walking. Cycling and unknown activity types are unsupported and must not be reclassified.
- Measurements: paired blood pressure, weight, body fat, lean body mass, and heart-rate samples that fall within an imported workout.
- The app requests read permissions only. It does not write records to Health Connect or request route/GPS data.
- Run background sync about hourly after one-time setup and permission grants. Android may defer scheduled work. Import the most recent 30 days by default; request Health Connect history access only when the user explicitly selects older dates.
- Exact repeat reads are idempotent. Keep and flag likely cross-source duplicates. Preserve conflicting values rather than merging or replacing them.
- Import a workout only when a reliable distance from the same source can be matched. A swim without a known meter/yard course unit can be retained as unclassified and excluded from course-specific totals. Summary swims do not create stroke PBs or length-based analysis.

## Proposed receiver

Proposed batch route:

`POST /wp-json/swimlog/v1/health-connect/import-batch`

The route name is provisional until the WordPress API is designed. Use HTTPS and an owner-bound bearer token with separate activity-write and private-measurement-write scopes. The receiver derives the owner from the token; the request must not contain an owner/user ID. The receiver must call Swim Log domain services and must not write tables directly.

A request contains:

- `contract_version`: integer, initially `1`.
- `batch_id`: a client-generated opaque identifier for tracing a retry; it contains no health values or credentials.
- `workouts[]`: zero or more normalized workouts.
- `measurements[]`: zero or more private observations.

The server may partially accept a batch. It must return an outcome for every item so the client can retry only retryable failures. A network timeout is safe to retry because each record has a stable source key.

## Source identity and idempotency

Health Connect record IDs and origin package names stay private. The Android app derives a 64-character lowercase SHA-256 key locally:

`source_record_key = SHA-256("health-connect:v1" + NUL + origin_package + NUL + record_type + NUL + record_id [+ NUL + sample_time_utc])`

For a heart-rate sample, include the sample instant so samples from the same Health Connect series record have separate identities. The raw Health Connect record ID is not sent.

The server enforces uniqueness by authenticated owner + provider (`health_connect`) + `source_record_key`. It stores the origin package and key only in owner-private import metadata; neither belongs in public workout projections. A resend with the same key and same content returns `duplicate` without a second write.

A resend with the same key but different content returns `conflict`; it must not overwrite the stored value. Two records from different origin packages with matching type/time/value are both retained and flagged as possible duplicates. The app/server do not silently merge them. Health Connect deletions do not delete Swim Log records in v1.

## Workout item

Each `workouts[]` item has:

| Field | Required | Meaning |
| --- | --- | --- |
| `source_record_key` | Yes | Derived idempotency key for the Health Connect `ExerciseSessionRecord`. |
| `source_package` | Yes | Health Connect `dataOrigin.packageName`; private provenance. |
| `source_record_type` | Yes | `ExerciseSessionRecord`. |
| `activity_type` | Yes | One of `swimming_pool`, `swimming_open_water`, `running`, `running_treadmill`, `walking`. |
| `start_time_utc`, `end_time_utc` | Yes | ISO-8601 UTC instants with `Z`; end must be after start. |
| `start_offset_seconds`, `end_offset_seconds` | No | Health Connect offsets, when supplied. Do not manufacture an offset. |
| `calendar_date` | Yes (nullable) | Source-local date when a valid offset exists; otherwise the owner's configured Swim Log timezone date. Null only when the date is unresolved. |
| `date_basis` | Yes | `source_offset` or `owner_timezone`; use `unresolved` only if neither date can be established. |
| `elapsed_time_ms` | Yes | End instant minus start instant. This is elapsed time, not moving time. |
| `distance_value`, `distance_unit` | Yes | Source distance and explicit unit, matched from a distance record of the same origin and unambiguous session interval. |
| `distance_m` | Yes | Canonical meters, converted from the explicit source unit without heuristic rounding. |
| `course_unit` | Yes for swim; otherwise `not_applicable` | `m`, `yd`, or `unclassified`. Set `unclassified` if the source does not establish pool course units. |
| `distance_source_record_key` | Yes | Key for the matched Health Connect distance record. |
| `heart_rate_samples[]` | No | Matched samples from the same origin, clipped to the workout interval `[start,end)`. |

Accept only the listed activity types. Health Connect exercise type is the classifier; GPS presence/absence must never determine activity type. Cycling, unknown exercise types, and other activities return `unsupported_activity_type`.

Use a distance from embedded session laps only if it is explicitly part of that session and can be attributed to the same source. Otherwise match `DistanceRecord` intervals by origin and time. Do not add lap distance and interval distance together. If no unique reliable match exists, do not import the workout; return `distance_not_matched`.

Health Connect does not guarantee pool length, stroke by length, swim splits, moving time, or GPS in an exercise session. Do not infer them. A summary-only swim with matched distance is valid and does not contribute fabricated lap/stroke details or stroke-specific performance records. Open-water routes are outside v1.

Meters are the canonical storage unit, but meter and yard course totals remain separate. Only assign `course_unit=m` or `yd` if the input explicitly establishes that course unit. An unclassified swim is not included in either course-specific total.

### Heart-rate sample

Each workout sample contains:

| Field | Meaning |
| --- | --- |
| `source_record_key` | Derived key using origin, `HeartRateRecord`, record ID, and sample instant. |
| `time_utc` | Sample instant as ISO-8601 UTC. |
| `beats_per_minute` | Source BPM value as a decimal. |

Include only samples within the matched workout interval and from a matchable source. Do not import day-long heart-rate records, calculate medical conclusions, or infer missing samples. Preserve each raw sample; do not replace the series with a derived average or maximum.

## Measurement item

Each `measurements[]` item has:

| Field | Required | Meaning |
| --- | --- | --- |
| `source_record_key` | Yes | Derived idempotency key for the Health Connect measurement record. |
| `source_package` | Yes | Health Connect origin package; private provenance. |
| `source_record_type` | Yes | `BloodPressureRecord`, `WeightRecord`, `BodyFatRecord`, or `LeanBodyMassRecord`. |
| `observed_at_utc` | Yes | Measurement instant as ISO-8601 UTC. |
| `source_offset_seconds` | No | Verified source offset, if present. |
| `calendar_date` | Yes (nullable) | Source-local date when offset is present; otherwise owner's configured Swim Log timezone date. Null only when the date is unresolved. |
| `date_basis` | Yes | `source_offset`, `owner_timezone`, or `unresolved`. |
| `category` | Yes | `blood_pressure`, `weight`, or `body_composition`. |
| `values` | Yes | Typed values and explicit normalized units below. |

Allowed measurement values:

| Record type | Category | Accepted values |
| --- | --- | --- |
| `BloodPressureRecord` | `blood_pressure` | Required pair: `systolic` and `diastolic`, each `mmHg`. Do not split the pair or add pulse from another record. |
| `WeightRecord` | `weight` | `weight`, `kg`; retain the source value/unit in private provenance when available. |
| `BodyFatRecord` | `body_composition` | `body_fat`, `%`. |
| `LeanBodyMassRecord` | `body_composition` | `lean_body_mass`, `kg`. This is distinct from muscle mass and requires an explicit receiver field. |

Send decimal values at source precision with explicit units. Reject malformed, non-finite, missing-unit, or unsupported values; do not use magnitude-based unit guesses, calculate BMI, average readings, or apply medical interpretation. Body-fat and lean-mass records remain separate observations unless the source provides an explicit common measurement identity.

All measurements are private and owner-scoped. They must not be exposed through public workout endpoints, sharing links, or cross-owner administrator access.

## Response and errors

Successful HTTP `200` responses contain one result per input item:

- `accepted`: stored for this owner.
- `duplicate`: same source key and content already stored.
- `possible_duplicate`: retained, with a cross-source match flag.
- `rejected`: invalid or unsupported item; not stored.
- `conflict`: same source key with changed content; existing data is unchanged.

Each result contains `source_record_key`, `status`, and, when needed, `code` and `retryable`. Do not echo measurement values, bearer tokens, raw Health Connect IDs, SQL errors, or full request bodies in error responses or logs.

Batch-level HTTP errors:

| HTTP | Code examples | Client behavior |
| --- | --- | --- |
| `400` | `invalid_contract`, `invalid_item` | Do not retry unchanged item; retain a safe error code. |
| `401` | `invalid_token`, `revoked_token` | Stop syncing and request account reconnection. |
| `403` | `missing_scope`, `access_disabled` | Stop affected category and explain the required authorization. |
| `409` | `idempotency_conflict` | Preserve stored data; queue conflict for later review. |
| `413` | `batch_too_large` | Split into smaller batches; do not discard items. |
| `429` | `rate_limited` | Retry after `Retry-After`, if provided. |
| `5xx` | `server_error` | Retry with bounded exponential backoff. |

Network errors and timeouts are retryable with the same keys. Permanent validation/auth errors are not. Logs may contain only aggregate counts, safe error codes, and opaque batch IDs.

## Receiver prerequisites

This contract requires server work before end-to-end sync is possible:

1. A new owner-bound bearer token with activity and private-measurement write scopes, plus issue/revoke handling.
2. A new POST batch endpoint with per-item results, bounded payload size, owner-derived writes, and the idempotency rules above.
3. Health Connect provider support in private measurement storage.
4. A private persistence path for heart-rate samples restricted to matched workout windows.
5. An explicit `lean_body_mass` receiver field and a separate `unclassified` swim course-unit state, or equivalent non-lossy representations.
6. Regression tests for ownership, permissions, units, timestamps, duplicates, conflicts, partial batches, retries, and public/private separation.

No endpoint names, schema changes, credentials, or server behavior described here are implemented by adding this Android contract document.
