# Attnd Android

Kotlin + Jetpack Compose app that lets attendees discover and follow live events. Consumes the Attnd REST API served by **[attnd-web](https://github.com/apbeers/Attnd-Web)**.

## Tech Stack

- **Kotlin** with **Jetpack Compose** (Material3)
- **Retrofit2 + OkHttp5** — REST networking
- **Gson** — JSON serialization
- **Coil** — async image loading
- **Google Maps Compose** — map view
- **CameraX + ML Kit** — QR code scanning
- **Google Play Integrity** — device attestation for API authentication
- **Play Install Referrer** — deep link on first install via Play Store
- **compileSdk / targetSdk**: 37 — **minSdk**: 33 (Android 13+)
- **Application ID**: `com.arcu.attnd`

## Project Structure

```
app/
  build.gradle              # App-level build; reads API keys from local.properties
  src/main/
    AndroidManifest.xml
    java/com/arcu/attnd/    # All Kotlin source
    res/                    # Resources (drawables, strings, etc.)
build.gradle                # Project-level build config
version.properties          # VERSION_CODE — increment for each release
signing.properties          # (not committed) Keystore config for release signing
local.properties            # (not committed) ATTND_API_KEY, MAPS_API_KEY
```

## Authentication

API requests use `Authorization: Bearer <token>`. Token resolution:
1. **Play Integrity JWT** — issued by `/api/v1/attest/android/` after verifying a Play Integrity token; used in production builds.
2. **`BuildConfig.ATTND_API_KEY`** — static fallback for dev/debug builds, read from `local.properties` or the `ATTND_API_KEY` environment variable at build time.

On a 401 the stored JWT is invalidated and the request retries once after re-attesting.

## App Flow

```
Launch
├── Check Play Install Referrer for event_code (first install via QR → Play Store link)
│   └── If found → skip home, deep link directly into event
│
Home screen
├── Request location permission
├── GET /nearby_events/<lat>/<lng>/
└── QR scanner (CameraX + ML Kit)
    └── Scan → parse /join/<code>/ URL → GET /lookup/?q=<code>
│
Join event
├── POST /join/<deviceId>/ { platform: "android" }
└── Load locations, schedule, notifications
│
Main screen (tabs)
├── Locations     → GET /locations/
├── Schedule      → GET /schedule/
├── Map           → Google Maps with location pins
├── Flyer         → load flyer_url
└── Notifications → GET /notifications/
│
Leave event
└── POST /leave/<deviceId>/
```

## API Key & Secrets Setup

Create `local.properties` in the project root (not committed to git):
```properties
ATTND_API_KEY=your_dev_key_here
MAPS_API_KEY=your_maps_key_here
```

These are injected into `BuildConfig` at build time via `app/build.gradle`.

For release signing, create `signing.properties` in the project root (not committed):
```properties
STORE_FILE=path/to/keystore.jks
STORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

## Device Identity

A UUID is generated on first launch and persisted on-device. This stable ID is used as `device_id` in all join/leave/push subscription API calls.

## Deep Linking (App Links)

`/.well-known/assetlinks.json` (served by attnd-web) configures Android App Links so `https://attnd.app/join/<code>/` routes directly into the app when installed.

On **first install via the Play Store QR flow**, read the install referrer using the Play Install Referrer API to extract `event_code` and auto-join without the user having to search.

## Demo Event Banner

When `event.paid == false` the event is a demo/trial created by the scraper. Always show a prominent banner so attendees know it is not a live production event.

## Version Management

Version code lives in `version.properties`:
```properties
VERSION_CODE=42
```

Increment `VERSION_CODE` before each Play Store release. `versionName` is automatically set to `1.0.<VERSION_CODE>`.

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing.properties)
./gradlew assembleRelease
# or
./build_release.command
```

## Running Tests

```bash
./gradlew test
# or
./run_tests.command
```

## Release

See `RELEASE_SETUP.md` for Play Store upload instructions.

## Related Repos

- **[attnd-web](https://github.com/apbeers/Attnd-Web)** — Django backend + REST API (see `API.md` for full endpoint reference)
- **[attnd-ios](https://github.com/apbeers/Attnd-iOS)** — SwiftUI iOS client
