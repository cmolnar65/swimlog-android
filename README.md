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

Open the app and select **Sign in to Swim Log**. The app opens the HTTPS WordPress site in your browser; sign in there and authorize **Swim Log Android**. The app never asks for your WordPress password and does not require copying a token. It checks the returned OAuth state, exchanges the five-minute one-time PKCE code over HTTPS, and stores the owner-scoped token encrypted with an Android Keystore AES-GCM key. App backups are disabled. The short-lived PKCE verifier is encrypted on-device until the browser returns. Do not share sign-in screenshots or logs. The app never logs authorization codes, tokens, request bodies, or health values.

The app uses the fixed Swim Log client `swimlog_android` and callback `com.cmolnar65.swimlog:/oauth2redirect`. A successful new authorization replaces the previous single Health Connect app token for that Swim Log account. Canceling or failing authorization leaves the current saved token in place. **Forget saved connection** removes it from this device; revoke the server token separately in Swim Log settings if you also want to invalidate it.

The app asks for read access only to supported exercise sessions, distance, heart-rate samples, weight, body fat, lean body mass, and blood pressure. To sync periodically, also enable Health Connect background read access. Android may defer scheduled work. Without it, use **Sync now** while the app is open. Sync reads a rolling 30-day window, leaving the server's source-ID idempotency to protect against repeats. No Health Connect history or route permission is requested.

The app omits unclassified exercise types, unsupported cycling, ambiguous distance associations, and ambiguous heart-rate associations. Distance can be associated across Health Connect source apps when its interval covers at least 90% of the workout and at least 80% of the shorter interval; non-overlapping contained segments from one source are summed only when they cover at least 90% of the workout. If supported totals from different sources differ by more than 15% of the larger total, distance is left blank. Within that tolerance the app selects one stable source total and never averages or adds totals across apps. It does not infer activity from GPS, calculate health metrics, combine conflicting records, or create swim laps, pool lengths, splits, or routes. Denying a data-type permission excludes that type from future syncs. The server token determines the owner; the app sends no owner ID.

Before upload, the app suppresses strong mirrored workout candidates from different Health Connect sources when activity, environment, and heart-rate samples agree, distances are within 10% when both are present, and intervals overlap by at least 90% of the shorter session; the shorter session must also be at least 80% as long as the longer one. Missing distance is treated as unknown, not as a mismatch, and a mapped distance is preferred when selecting the retained copy. Same-source records and candidates with conflicting measured content remain separate. The sync summary reports how many mirrored copies were skipped.

## Build and test checks

```sh
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

GitHub Actions runs both checks and uploads only the debug APK. There is no real-device or end-to-end Health Connect test in CI. Use synthetic test records and the Swim Log test account for acceptance; do not use private health records as test fixtures.
