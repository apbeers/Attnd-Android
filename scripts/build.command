#!/bin/bash
# Build a debug APK. Useful to verify compilation after code changes.
cd "$(dirname "$0")/.."
echo "Building debug APK..."
./gradlew assembleDebug --no-daemon 2>&1
STATUS=$?
[ $STATUS -eq 0 ] && echo "✅ Build succeeded. APK at app/build/outputs/apk/debug/app-debug.apk" \
                  || echo "❌ Build failed."
echo "Press any key to close."
read -n 1
