#!/bin/bash
cd "$(dirname "$0")"

# 1. Sync and Increment Version
echo "🔄 Incrementing version and pushing to git..."
./commit_and_push.command

if [ $? -ne 0 ]; then
    echo "❌ Git update failed. Aborting build."
    exit 1
fi

# 2. Build the bundle
echo ""
echo "🏗️ Building signed release bundle (Build $(grep 'VERSION_CODE=' version.properties | cut -d'=' -f2))..."
./gradlew bundleRelease --warning-mode all

if [ $? -eq 0 ]; then
    # Find the bundle path
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
