#!/bin/bash
cd "$(dirname "$0")"
rm -f .git/index.lock
rm -f .git/HEAD.lock
git config user.email "apbeers@icloud.com"
git config user.name "Andrew"
git add -A
git commit -m "feat: complete app modernization and feature implementation

- Migrated to Material 3 and Jetpack Compose.
- Implemented core screens: Launch, Map, Locations, Flyer, Notifications, and Options.
- Integrated Attnd API with Retrofit and authenticated networking.
- Added deep linking support for event joining via URL/QR code.
- Configured persistent event state and auto-resume.
- Enhanced map with re-centering logic, markers, and custom FABs.
- Modernized app icons (Adaptive) and added branded Splash Screen (API 31+).
- Added unit and integration tests with reorganized structure.
- Updated CI pipeline to JDK 21 and latest GitHub Actions.
- Secured API keys using BuildConfig and secrets-gradle-plugin."
git push origin develop
echo ""
echo "Done! You can now open a PR from develop -> main to trigger the Android CI pipeline."
