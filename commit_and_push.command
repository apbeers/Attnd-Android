#!/bin/bash
cd "$(dirname "$0")"
rm -f .git/index.lock
git config user.email "apbeers@icloud.com"
git config user.name "Andrew"
git add -A
git commit -m "Add my location button to map + implement Android nearby events

- MainMapScreen: FAB with MyLocation icon, permission request on tap,
  FusedLocationProvider zoom, MapProperties(isMyLocationEnabled)
- LaunchScreen: implemented Find Nearby Events with real location permission,
  FusedLocationProvider, getNearbyEvents API call, clickable event cards
- OptionsScreen: location toggle lifted to MainActivity, permission
  request when toggling on from denied state
- MainActivity: locationEnabled hoisted, wired to all three screens

Previously committed (integration tests + CI):
Add integration tests + GitHub Actions Android CI

Integration tests (app/src/test/.../integration/AttndApiTest.kt):
- 25 tests hitting live attnd.app API using fixture data
- Event lookup by code and name for all 3 events
- Fetch by ID, 404 error cases
- Locations list (count=10 per event), single location detail
- Notifications: only sent=true returned (2 per event), correct titles
- Device join/leave round-trip with unique UUID
- Nearby events returns correct event near each city's coordinates
- Response envelope count matches results

GitHub Actions (.github/workflows/android.yml):
- Triggers on push to main only
- Runs on ubuntu-latest with JDK 21
- Passes ATTND_API_KEY from GitHub secrets via -P flag
- Uploads test reports as artifact (14-day retention)"
git push origin develop
echo ""
echo "Done! You can now open a PR from develop -> main to trigger the Android CI pipeline."
echo "Press any key to close."
read -n 1
