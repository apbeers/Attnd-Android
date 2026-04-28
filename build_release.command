#!/bin/bash
cd "$(dirname "$0")"

echo "🏗️ Building signed release bundle..."
./gradlew bundleRelease

if [ $? -eq 0 ]; then
    # Find the bundle path. Usually it's app-release.aab, but we can list it to be sure.
    BUNDLE_PATH=$(find app/build/outputs/bundle/release -name "*.aab" | head -n 1)

    echo ""
    echo "✅ Build Successful!"
    if [ -f "$BUNDLE_PATH" ]; then
        echo "📍 Release Bundle Path: $(pwd)/$BUNDLE_PATH"
    else
        echo "⚠️ Could not locate the .aab file, but the build reported success."
    fi
else
    echo ""
    echo "❌ Build Failed. Please check the logs above."
    exit 1
fi

echo ""
echo "Press any key to close."
read -n 1
