#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

./gradlew --no-daemon --stacktrace assembleDebug
apk="$repo_root/app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk"
mkdir -p "$repo_root/dist"
cp "$apk" "$repo_root/dist/swimlog-health-connect-debug.apk"
echo "Test APK: $repo_root/dist/swimlog-health-connect-debug.apk"
echo "Install with: adb install -r '$repo_root/dist/swimlog-health-connect-debug.apk'"
