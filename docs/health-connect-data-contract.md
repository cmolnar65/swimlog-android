# Health Connect import data contract

**Status:** Version 1 contract implemented by the Android test client and the WordPress `develop` receiver. End-to-end real-device acceptance remains pending.

## Flow and permissions

- One-way flow: Health Connect → Android app → the authenticated owner's private Swim Log account.
- The app requests read permissions only. It does not write to Health Connect or request route/GPS data.
- Supported workout classifications are pool swimming, open-water swimming, running, treadmill running, and walking. Cycling and unknown activities are unsupported.
- Supported private measurements are weight, body composition (weight, body fat, lean body mass), paired blood pressure, and heart-rate samples matched to an imported workout.
- Exact repeat reads are idempotent. Keep and flag likely cross-source duplicates. Preserve conflicts; never silently merge or replace a record.
- Before upload, Android suppresses strong cross-source workout mirror candidates when activity, environment, and heart-rate samples agree, distances are within 10% when present, and intervals overlap by at least 99% of the shorter interval. If both records omit distance, stricter boundary tolerances apply (start within 5 seconds; end within 1 second); if only one has distance, both are retained. Same-source records and records with conflicting measured content remain separate; the sync summary reports skipped mirror candidates.
- Do not infer an activity subtype from GPS presence or absence. Do not invent pool lengths, strokes, splits, moving time, or routes.

A periodic background sync requires the user to grant Health Connect's separate `READ_HEALTH_DATA_IN_BACKGROUND` permission in addition to each requested data-type read permission. Check that the feature is available and permission remains granted. If unavailable or denied, sync while the app is in the foreground and explain the limitation; do not promise hourly background imports. Android may defer scheduled work.

By default, Health Connect permits reads only for records from up to 30 days before the user first grants a data-type permission. Request `READ_HEALTH_DATA_HISTORY` only after the user explicitly chooses older history and only if supported. Ask separately before sending older records to Swim Log.

## WordPress receiver

The receiver is implemented on the Swim Log WordPress `develop` branch:

`POST /wp-json/swimlog/v1/health-connect/import-batch`

Use HTTPS and the dedicated owner-scoped bearer token generated under **Swim Training → Swimmer Configuration → Health Connect App Sync**. The token is shown once, can be revoked/replaced, and is separate from WordPress Application Passwords. Never put it in logs, screenshots, analytics, or source control. The server derives the owner from the token; never send an owner ID.

A v1 JSON request contains `contract_version: 1`, a safe opaque `batch_id`, and `workouts[]` and/or `measurements[]`. Limits are 512 KiB per request, 50 workouts, 500 measurements, and 2,000 heart-rate samples per workout. Split larger batches without dropping records.

## Workout request fields

Each workout accepts only these fields:

| Field | Requirement and meaning |
| --- | --- |
| `record_id` | Required stable Health Connect record ID. Sent over HTTPS so the server can derive identity; the server stores only a hash, not this ID in plaintext. Keep it private on the device. |
| `origin` | Required Health Connect `dataOrigin.packageName`; private provenance. |
| `activity` | Required: `pool_swimming`, `open_water_swimming`, `running`, `running_treadmill`, or `walking`. |
| `environment` | Optional `indoor` or `outdoor`, supported only for running. Treadmill running is stored as indoor. |
| `start_time`, `end_time` | Required ISO-8601 timestamps with `Z` or explicit offset; end must follow start. Preserve source offsets. |
| `distance_m` | Optional positive finite distance in meters. Only send when reliably matched to this workout and origin; do not combine overlapping sources. |
| `pool_length_value`, `pool_length_unit` | Optional pair; unit is `m` or `yd`. Include only when the source explicitly establishes the pool course. Otherwise omit both; Swim Log keeps course unclassified. |
| `heart_rate_samples[]` | Optional samples with `record_id`, matching `origin`, `time`, and integer `bpm` (20–250). Use unique stable sample IDs; include only samples within the workout interval. |

The receiver stores imported workouts as private. A workout may be retained without distance, but it must not count toward distance totals. Summary-only swims do not create stroke or length-based performance data. The receiver does not calculate average or maximum heart rate from samples.

## Measurement request fields

Each measurement accepts only `record_id`, `origin`, `category`, `time`, and `values`. Use ISO-8601 time with `Z` or an explicit offset. The `values` object maps each supported measurement name to an explicit value and unit, for example:

```json
{"weight":{"value":"72.5","unit":"kg"}}
```

| Category | Allowed values |
| --- | --- |
| `weight` | `weight` in `kg` |
| `body_composition` | `weight` in `kg`, `body_fat` in `%`, and/or `lean_body_mass` in `kg` |
| `blood_pressure` | `systolic` and `diastolic`, both in `mmHg` |

Send decimal values at source precision with explicit units. Reject malformed, non-finite, missing-unit, or unsupported values. Do not guess units, calculate BMI, average readings, or apply medical interpretation. Measurements remain private and owner-scoped.

## Identity, duplicates, and responses

Send the Health Connect `record_id` and `origin` only in the HTTPS request. The WordPress receiver derives an owner-scoped source hash and does not store the raw record ID in plaintext. For heart-rate samples, each sample must have a stable unique record ID. A repeat with identical identity and content returns `duplicate`; changed content under an existing identity is a `conflict` and must not overwrite stored data. Cross-source matches are retained and flagged as possible duplicates.

HTTP 200 returns an outcome for each record. Current outcomes include `added`, `duplicate`, `conflict`, `retry`, and `rejected`. Retry only transient `retry` outcomes with the same identifiers. Do not retry a conflict using a new identity. Top-level errors include HTTP 400 (invalid contract), 401 (invalid token), 403 (HTTPS required), 409 (conflict), and 413 (request/count limits). Do not log request bodies, bearer headers, measurement values, or heart-rate values.

## Implementation prerequisites and validation status

The WordPress receiver and owner-scoped token handling exist on plugin `develop`. The Android test client now handles Health Connect availability, permission checks, best-effort background scheduling, bounded requests, HTTPS token transport, and receiver duplicate/conflict outcomes. The client has not yet passed a real-device Health Connect test against the installed Swim Log receiver.

The `swimlog-android` repository now contains a minimal native Android client, unit tests, a Gradle wrapper, an APK build script, and an Actions workflow that builds a debug APK artifact. The test APK is for manual installation and is not a Play Store or production release. Unit tests do not replace real-device Health Connect validation. End-to-end validation must use a safe test account with synthetic or non-sensitive records.

Official Android references:
- [Read Health Connect data, including background and history access](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Health Connect data types and additional read permissions](https://developer.android.com/health-and-fitness/health-connect/data-types)


## Android test client implementation notes

The initial client reads a rolling 30-day window. It requests only read permissions for exercise sessions, distance, heart rate, weight, body fat, lean body mass, and blood pressure; background access is requested separately and only when Health Connect reports the feature available. The user can sync in the foreground without background access. WorkManager runs periodic sync on a best-effort six-hour interval when background permission and network are available.

The debug APK is built with `./scripts/build-test-apk.sh`. That script invokes the checked-in Gradle wrapper, then copies the debug APK to the ignored `dist/` directory. GitHub Actions uploads the same test APK for seven days. Neither build path includes an app token or health records. The client stores only the owner-scoped token encrypted with Android Keystore, does not back up app data, and sends the token only to `https://cmolnar.com` over HTTPS.
