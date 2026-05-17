#!/bin/bash
# Build a debug APK. Useful to verify compilation after code changes.
cd "$(dirname "$0")/.."

# Use Android Studio's bundled JDK if no system Java is found
if ! command -v java &>/dev/null; then
  AS_JDK="$(ls -d /Applications/Android\ Studio.app/Contents/jbr/Contents/Home 2>/dev/null || true)"
  if [ -n "$AS_JDK" ]; then
    export JAVA_HOME="$AS_JDK"
    export PATH="$JAVA_HOME/bin:$PATH"
  else
    echo "❌ No Java found. Install Android Studio or a JDK from https://www.java.com"
    read -n 1
    exit 1
  fi
fi

echo "Building debug APK..."
./gradlew assembleDebug --no-daemon 2>&1
STATUS=$?
[ $STATUS -eq 0 ] && echo "✅ Build succeeded. APK at app/build/outputs/apk/debug/app-debug.apk" \
                  || echo "❌ Build failed."
echo "Press any key to close."
read -n 1
