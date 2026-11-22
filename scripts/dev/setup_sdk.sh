#!/usr/bin/env bash
# Installs the Android SDK pieces this project builds with into $ANDROID_HOME (default ~/android-sdk)
# and points local.properties at it. For fresh Linux containers (e.g. Claude cloud sessions); CI uses
# the runner's preinstalled SDK. Needs a JDK 21 on PATH (Ubuntu: apt-get install openjdk-21-jdk-headless)
# and network access to dl.google.com.
#   bash scripts/dev/setup_sdk.sh            then   ./gradlew testDebugUnitTest lintDebug assembleDebug
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
# Command-line tools 23.0 (Nov 2026): https://dl.google.com/android/repository/repository2-3.xml
CMDLINE_ZIP="commandlinetools-linux-16111833_latest.zip"
PACKAGES=(
  "platform-tools"
  "platforms;android-37.2"   # compileSdk 37, minor 2
  "build-tools;37.0.0"
)

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_ZIP"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# `yes` reports "Broken pipe" when sdkmanager stops reading; that is expected.
yes 2> /dev/null | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses > /dev/null || true
"$sdkmanager" --sdk_root="$ANDROID_HOME" "${PACKAGES[@]}"

# local.properties: sdk.dir plus empty keys (the build works without an API or Maps key).
props="$repo_root/local.properties"
touch "$props"
grep -q '^sdk.dir=' "$props" && sed -i "s#^sdk.dir=.*#sdk.dir=$ANDROID_HOME#" "$props" || echo "sdk.dir=$ANDROID_HOME" >> "$props"
grep -q '^ATTND_API_KEY=' "$props" || echo "ATTND_API_KEY=" >> "$props"
grep -q '^MAPS_API_KEY=' "$props" || echo "MAPS_API_KEY=" >> "$props"

echo "Android SDK ready in $ANDROID_HOME (export ANDROID_HOME=$ANDROID_HOME)"
