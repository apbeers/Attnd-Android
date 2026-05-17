#!/bin/bash
# Build a signed release APK using signing.properties at the repo root.
# Requires: signing.properties with STORE_FILE, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
# Usage: double-click, or: bash scripts/release.command
set -e
cd "$(dirname "$0")/.."

# Ensure JAVA_HOME is set (prefer Android Studio's bundled JDK on macOS)
if [ -z "$JAVA_HOME" ] || ! [ -x "$JAVA_HOME/bin/java" ]; then
  AS_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [ -d "$AS_JDK" ]; then
    export JAVA_HOME="$AS_JDK"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Using Android Studio JDK: $JAVA_HOME"
  elif ! command -v java &>/dev/null; then
    echo "❌ No Java found. Install Android Studio or a JDK from https://www.java.com"
    read -n 1
    exit 1
  fi
fi

if [ ! -f signing.properties ]; then
  echo "❌ signing.properties not found at repo root."
  echo "Create it with your keystore details — see scripts/release.command for format."
  read -n 1
  exit 1
fi

echo "Building signed release APK..."
./gradlew assembleRelease --no-daemon 2>&1
STATUS=$?
if [ $STATUS -eq 0 ]; then
  APK="app/build/outputs/apk/release/app-release.apk"
  echo "✅ Build succeeded: $APK"
  open "$(dirname "$APK")"
else
  echo "❌ Build failed."
fi
echo "Press any key to close."
read -n 1
