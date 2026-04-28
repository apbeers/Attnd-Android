#!/bin/bash
cd "$(dirname "$0")"

# 1. Increment Version Code in version.properties
if [ -f version.properties ]; then
    # Read current version
    VERSION_CODE=$(grep 'VERSION_CODE=' version.properties | cut -d'=' -f2)
    # Increment
    NEW_VERSION_CODE=$((VERSION_CODE + 1))
    # Write back to file (macOS compatible sed)
    sed -i '' "s/VERSION_CODE=.*/VERSION_CODE=$NEW_VERSION_CODE/" version.properties
    echo "🚀 Incremented Build Version to: $NEW_VERSION_CODE"
else
    echo "VERSION_CODE=20" > version.properties
    echo "🚀 Created version.properties with Build Version: 20"
fi

# 2. Git Operations
rm -f .git/index.lock
rm -f .git/HEAD.lock
git config user.email "apbeers@icloud.com"
git config user.name "Andrew"

git add -A
git commit -m "feat: app update (build $(grep 'VERSION_CODE=' version.properties | cut -d'=' -f2))"
git push origin develop

echo ""
echo "✅ Done! Build version incremented and changes pushed to develop."
echo "You can now open a PR to main to trigger the CI pipeline."
