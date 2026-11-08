# Swim Log Health Connect test client

An Android test client for the one-way, read-only Health Connect import contract. It sends selected Health Connect workouts and measurements to the authenticated owner's private Swim Log WordPress account over HTTPS. It does not write to Health Connect and does not use either Swim Log source repository as a build dependency.

## Build a phone-installable test APK

Install Android Studio with Android SDK Platform 36 and a Java 17 JDK. The checked-in Gradle wrapper pins Gradle 8.11.1; Android Gradle Plugin is 8.9.1. Then run:

```sh
./scripts/build-test-apk.sh
adb install -r dist/swimlog-health-connect-debug.apk
```

The script builds the debug variant signed with the local Android debug key and copies it to `dist/swimlog-health-connect-debug.apk`. It is for personal testing; it is not a Play Store or production package. `.gitignore` excludes `dist/`, local Gradle/Android files, and signing credentials. GitHub Actions also builds the debug APK and stores it as a short-lived workflow artifact for download; no token or health data is used in CI.

## Test connection

Open the app, enter the Swim Log site using HTTPS and the dedicated owner-scoped app token, save, and connect Health Connect. The token is encrypted with an Android Keystore AES-GCM key; app backups are disabled. Do not paste the token into issue reports, screenshots, or logs. The app never logs request bodies or health values.

The app asks for read access only to supported exercise sessions, distance, heart-rate samples, weight, body fat, lean body mass, and blood pressure. To sync periodically, also enable Health Connect background read access. Android may defer scheduled work. Without it, use **Sync now** while the app is open. Sync reads a rolling 30-day window, leaving the server's source-ID idempotency to protect against repeats. No Health Connect history or route permission is requested.

The app omits unclassified exercise types, unsupported cycling, ambiguous distance associations, and ambiguous heart-rate associations. It does not infer activity from GPS, calculate health metrics, combine conflicting records, or create swim laps, pool lengths, splits, or routes. Denying a data-type permission excludes that type from future syncs. The server token determines the owner; the app sends no owner ID.

Before upload, the app suppresses mirrored workout copies from different Health Connect sources only when the mapped activity, environment, exact start/end timestamps, distance, and heart-rate sample times/values are identical. Copies with differing content remain separate; the app does not merge conflicting data. The sync summary reports how many exact mirrors were skipped.

## Build and test checks

```sh
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

GitHub Actions runs both checks and uploads only the debug APK. There is no real-device or end-to-end Health Connect test in CI. Use synthetic test records and the Swim Log test account for acceptance; do not use private health records as test fixtures.
