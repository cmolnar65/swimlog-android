# Health Connect import data contract

**Status:** Version 1 contract implemented by the Android test client and the WordPress `develop` receiver. End-to-end real-device acceptance remains pending.

## Flow and permissions

- One-way flow: Health Connect → Android app → the authenticated owner's private Swim Log account.
- The app requests read permissions only. It does not write to Health Connect. Readable workout details include laps, segments, speed, active/total calories, elevation gain, floors climbed, walking/running step cadence, cycling cadence, and power where available; routes use Health Connect's separate route-consent flow.
- Supported workout classifications are pool swimming, open-water swimming, running, treadmill running, walking, and cycling. Stationary biking is stored as cycling with an explicit indoor environment; generic biking does not assume indoor/outdoor based on GPS.
- Supported private measurements are weight, body composition (weight, body fat, lean body mass), paired blood pressure, and heart-rate samples matched to an imported workout.
- Exact repeat reads are idempotent. Keep and flag likely cross-source duplicates. Preserve conflicts; never silently merge or replace a record.
- Distance records may be associated across source apps by time interval. Contained non-overlapping segments from one source are summed only when their combined intervals cover at least 90% of the workout; a near-session record must overlap at least 90% of the shorter interval and the shorter interval must be at least 80% of the longer. If supported per-source totals differ by more than 15% of the larger total, leave distance blank. If they agree within 15%, select one stable source total without averaging or adding across sources.
- Before upload, Android suppresses strong cross-source workout mirror candidates when activity, environment, and heart-rate samples agree, distances are within 10% when both are present, and intervals overlap by at least 90% of the shorter interval; the shorter duration must also be at least 80% of the longer. Missing distance is unknown, not a mismatch, and a mapped distance is preferred when selecting the retained copy. Same-source records and records with conflicting measured content remain separate; the sync summary reports skipped mirror candidates.
- Do not infer an activity subtype from GPS presence or absence. Do not invent pool lengths, strokes, splits, moving time, or routes. Bike, run, walk, and swim remain distinct; cycling totals do not enter swimming analytics.
- Exercise-session laps are sent with their exact start/end times and optional Health Connect length. Exercise segments retain the Health Connect segment-type integer, recognized label, and interval, including stroke-segment classifications when present. Speed, cadence, and power samples are attached only to exactly one overlapping supported workout from the same Health Connect source. Energy, elevation, and floors are summed only from contained, non-overlapping intervals from that same source; ambiguous overlaps are omitted. These metrics are not inferred from GPS or distance. Step-count totals are not read.
- Health Connect route data is requested only during a foreground sync. For each route that requires consent, Android may show its route-specific consent dialog; denying a route does not block the workout or other details. Background sync does not prompt for routes and omits them when Android returns `ConsentRequired`.

A periodic background sync requires the user to grant Health Connect's separate `READ_HEALTH_DATA_IN_BACKGROUND` permission in addition to each requested data-type read permission. Check that the feature is available and permission remains granted. If unavailable or denied, sync while the app is in the foreground and explain the limitation; do not promise hourly background imports. Android may defer scheduled work.

By default, Health Connect permits reads only for records from up to 30 days before the user first grants a data-type permission. Request `READ_HEALTH_DATA_HISTORY` only after the user explicitly chooses older history and only if supported. Ask separately before sending older records to Swim Log.

## WordPress receiver

The receiver is implemented on the Swim Log WordPress `develop` branch:

`POST /wp-json/swimlog/v1/health-connect/import-batch`

The Android app obtains its owner-scoped bearer token through browser authorization; it never collects a WordPress password. It creates a random PKCE verifier and state, opens `https://cmolnar.com/wp-admin/admin.php?page=swimlog-health-connect-authorize` with client `swimlog_android`, the fixed callback `com.cmolnar65.swimlog:/oauth2redirect`, and an S256 challenge, then verifies the returned state. It exchanges the short-lived one-time code at `POST https://cmolnar.com/wp-json/swimlog/v1/health-connect/token` over HTTPS. The code expires after five minutes and the server consumes it once. The response token is stored encrypted with Android Keystore and sent only over HTTPS to the import endpoint. Never log, screenshot, or commit authorization codes, verifiers, tokens, or health values. The server derives the owner from the WordPress browser session during authorization and from the bearer token during import; the app never sends an owner ID.

A v1 JSON request contains `contract_version: 1`, a safe opaque `batch_id`, and `workouts[]` and/or `measurements[]`. Limits are 2 MiB per request, 50 workouts, 500 measurements, 2,000 heart-rate samples, 1,000 laps, 1,000 segments, 5,000 route points, and up to 2,000 samples in each bounded metric series per workout. The Android app sends one workout per request to keep large routes bounded.

## Workout request fields

Each workout accepts only these fields:

| Field | Requirement and meaning |
| --- | --- |
| `record_id` | Required stable Health Connect record ID. Sent over HTTPS so the server can derive identity; the server stores only a hash, not this ID in plaintext. Keep it private on the device. |
| `origin` | Required Health Connect `dataOrigin.packageName`; private provenance. |
| `activity` | Required: `pool_swimming`, `open_water_swimming`, `running`, `running_treadmill`, `walking`, or `cycling`. |
| `environment` | Optional `indoor` or `outdoor` for running or cycling. Treadmill and stationary cycling are stored as indoor. Generic biking does not claim outdoor unless Health Connect classifies it explicitly. |
| `start_time`, `end_time` | Required ISO-8601 timestamps with `Z` or explicit offset; end must follow start. Preserve source offsets. |
| `distance_m` | Optional positive finite distance in meters. Match by sufficiently strong time-interval coverage even when the distance source differs from the workout source. Resolve per-source totals conservatively; leave blank for conflicting or overlapping candidates. |
| `pool_length_value`, `pool_length_unit` | Optional pair; unit is `m` or `yd`. Include only when the source explicitly establishes the pool course. Otherwise omit both; Swim Log keeps course unclassified. |
| `heart_rate_samples[]` | Optional samples with `record_id`, matching `origin`, `time`, and integer `bpm` (20–250). Use unique stable sample IDs; include only samples within the workout interval. |
| `details` | Optional object containing laps/splits, exercise segments, route points, total/active calorie summaries, speed summaries and samples, elevation gain, floors climbed, and step cadence, cycling cadence, and power samples. Each interval must fit inside its workout; all values are bounded and validated. Samples require an unambiguous same-source workout association. The receiver stores details in private, owner-scoped Health Connect activity metadata and exposes summaries, laps, segments, and route points only in the owner's private workout detail view. Route coordinates are never copied to public posts by this importer. |

The receiver stores imported workouts as private. A workout may be retained without distance, but it must not count toward distance totals. Cycling remains separate from swimming totals. Summary-only swims do not create stroke or length-based performance data; Health Connect segments are retained as source details and do not create invented pool lengths or personal-best results. The receiver does not calculate average or maximum heart rate from samples.

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

The WordPress receiver, browser consent page, and PKCE code exchange exist on plugin `develop`. The Android test client handles browser sign-in, callback state verification, encrypted PKCE state, HTTPS code exchange, Health Connect availability and permission checks, best-effort background scheduling, bounded requests, and receiver duplicate/conflict outcomes. The client has not yet passed a real-device authorization and Health Connect sync test against the installed Swim Log receiver.

The `swimlog-android` repository now contains a minimal native Android client, unit tests, a Gradle wrapper, an APK build script, and an Actions workflow that builds a debug APK artifact. The test APK is for manual installation and is not a Play Store or production release. Unit tests do not replace real-device Health Connect validation. End-to-end validation must use a safe test account with synthetic or non-sensitive records.

Official Android references:
- [Read Health Connect data, including background and history access](https://developer.android.com/health-and-fitness/health-connect/read-data)
- [Health Connect data types and additional read permissions](https://developer.android.com/health-and-fitness/health-connect/data-types)


## Android test client implementation notes

The client reads a rolling 30-day window. It requests read permissions for exercise sessions, distance, heart rate, speed, total/active calories, elevation, floors, step cadence (the Health Connect steps permission), cycling cadence, power, weight, body fat, lean body mass, and blood pressure; background access is requested separately and only when Health Connect reports the feature available. The user can sync in the foreground without background access. WorkManager runs periodic sync on a best-effort six-hour interval when background permission and network are available.

The debug APK is built with `./scripts/build-test-apk.sh`. That script invokes the checked-in Gradle wrapper, then copies the debug APK to the ignored `dist/` directory. GitHub Actions uploads the same test APK for seven days. Neither build path includes an app token or health records. The client stores the owner-scoped token and any active PKCE verifier encrypted with Android Keystore, does not back up app data, and sends codes and tokens only to `https://cmolnar.com` over HTTPS.
