# Attnd Android

Kotlin + Jetpack Compose app that lets attendees discover and follow live events. Consumes the Attnd REST API **v2** served by **[attnd-web](https://github.com/apbeers/Attnd-Web)** (contract: `API_V2.md`).

## Tech Stack & Dependency Policy

**No third-party libraries.** Allowed: the Kotlin stdlib and kotlinx (coroutines, serialization), AndroidX/Jetpack, and the Google first-party SDKs the app genuinely needs. Do not add Retrofit, OkHttp, Gson, Coil, etc.

- **Kotlin** with **Jetpack Compose** (Material3) and **Material3 adaptive** (`NavigationSuiteScaffold`, `ListDetailPaneScaffold`, `currentWindowAdaptiveInfo`)
- **Networking**: `HttpURLConnection` + coroutines (`data/net/HttpClient.kt`)
- **JSON**: kotlinx.serialization (`ignoreUnknownKeys`, every field defaulted)
- **Images**: in-house loader (`ui/components/RemoteImage.kt`: HttpURLConnection + BitmapFactory + LruCache)
- **AndroidX lifecycle** ViewModel + `collectAsStateWithLifecycle`
- **Google Maps**: `maps-compose` + `maps-compose-utils` (googlemaps first-party; marker clustering), Play Services location
- **CameraX + ML Kit** — QR code scanning
- **Google Play Integrity** — device attestation for API authentication
- **Play Install Referrer** — deep link on first install via Play Store
- **Firebase Cloud Messaging** — push (optional at build time, see below)
- **compileSdk / targetSdk**: 37 — **minSdk**: 35 (Android 15; we support roughly the last two years of Android, so no compatibility branches for older versions and no core-library desugaring — `java.time` is native)
- **Application ID**: `com.arcu.attnd` (debug builds: `com.arcu.attnd.debug`)

Exact dependency versions live in `app/build.gradle`.

## Project Structure

```
app/
  build.gradle                    # Reads API keys from local.properties; applies google-services only if app/google-services.json exists
  src/main/java/com/arcu/attnd/
    AttndApplication.kt           # App init (attestation, notification channel) + AppGraph (manual DI: repository, app scope)
    MainActivity.kt               # Single activity: intents (App Links, notification taps), foreground refresh loop
    domain/                       # Pure Kotlin, unit tested — no Android imports
      Models.kt                   #   Event, EventLocation, ScheduleItem, EventNotification, EventBundle, GeoPoint
      DisplayConfig.kt            #   Server-driven `display` object + client defaults
      EventTime.kt                #   ISO parsing and formatting in the EVENT's time zone
      ScheduleGrouping.kt         #   timeline / by_day / by_location / now_next, search, ended/now
      LocationGrouping.kt         #   category grouping, filters, mappable pins, bounds
      JoinLinks.kt                #   /join/<code>/ App Links + install-referrer parsing
    data/
      net/HttpClient.kt           #   HttpURLConnection client (no caching, so 304s reach us)
      api/AttndApi.kt             #   v2 endpoints; 401 → re-attest → retry once; ETag/If-None-Match
      api/ApiDtos.kt              #   @Serializable wire format (all fields nullable) + ApiJson config
      api/ApiMapper.kt            #   DTO → domain, applying defaults
      EventRepository.kt          #   Single source of truth (StateFlow bundle), join/leave/refresh, disk cache
      AttestedTokenProvider.kt    #   Bearer token: attestation JWT or static key
      InstallReferrer.kt          #   Play Install Referrer → event_code
      attest/AttestationApi.kt    #   Unauthenticated /api/v1/attest/ endpoints
      prefs/DevicePrefs.kt        #   device id, joined event id/code, ETag, push token, preferences
    push/                         # FCM service + notification channel / display
    ui/
      EventViewModel.kt           #   AppUiState + all user actions
      AttndApp.kt                 #   Root composable, theme (accent colour), EventShell with NavigationSuiteScaffold
      MapTab.kt                   #   Foldable-aware map + secondary pane
      components/                 #   RemoteImage, FoldAwareSplit, IdListDetail, banners, chips, QR scanner
      screens/                    #   Launch, Map, Locations, Schedule, Notifications, Flyer, Options
      theme/
  src/test/java/com/arcu/attnd/
    unit/                         # JVM tests: decoding, display defaults, grouping in event tz, HTTP client
    integration/AttndApiTest.kt   # Live v2 API (skipped when ATTND_API_KEY is not set)
version.properties                # VERSION_CODE — increment for each release
signing.properties                # (not committed) Keystore config for release signing
local.properties                  # (not committed) ATTND_API_KEY, MAPS_API_KEY
app/google-services.json          # (not committed) Firebase config for FCM
```

## Architecture

- **`EventRepository`** owns the joined event. `bundle: StateFlow<EventBundle?>` is the single source of truth; `sync: StateFlow<SyncState>` carries refresh progress and the last error. The raw bundle JSON is written to `filesDir/event_bundle.json` (atomic temp-file rename), so the app starts **offline** with the last content and refreshes in the background.
- **`EventViewModel`** combines repository state with UI-local state into one `AppUiState` (phase LOADING / LAUNCH / EVENT, selected tab, join/nearby state, one-shot focus requests and messages). Screens receive state slices + callbacks only; they hold no app state (only UI state like search text).
- Device ID: a UUID generated on first launch (`DevicePrefs.deviceId`), used for join/leave/devices and as `X-Device-Id`.

## API (v2)

Base `https://attnd.app/api/v2/` — see `API_V2.md`.

| Call | Use |
|------|-----|
| `GET events/nearby/?lat&lng` | Launch screen "Find nearby events" |
| `GET events/lookup/?q=` | Event code / exact name (QR codes, App Links, referrer) |
| `GET events/<id>/bundle/` | Everything shown; `If-None-Match` → `304`; `X-Device-Id` header |
| `POST events/<id>/join/` | `{device_id, platform, push_token, app_version}` |
| `POST events/<id>/leave/` | `{device_id}` |
| `POST devices/` | On launch and when push token / notification preference changes |

Bundle refresh: on join, when the app is resumed, pull-to-refresh, every 5 minutes while resumed on event days, and on a `content_updated` push. A `404` on the bundle clears the event and returns to the launch screen.

Models tolerate missing and unknown keys everywhere. Coordinates may be `null`: never pin those, and never treat `0,0` as real (`GeoPoint.of`). Schedule items may have no location ("General"). All times are shown in the event's `timezone`.

## Authentication

`Authorization: Bearer <token>`:
1. **Play Integrity JWT** — from `/api/v1/attest/android/` after verifying a Play Integrity token (production).
2. **`BuildConfig.ATTND_API_KEY`** — static fallback for dev/debug/emulator builds, from `local.properties` or the `ATTND_API_KEY` environment variable.

On a 401 the stored JWT is invalidated and the request is retried once after re-attesting (`AttndApi.send`). After an attestation failure, attestation is not retried for 10 minutes.

## Server-driven Display

`DisplayConfig` resolves the bundle's `display` object with the documented defaults:
tabs (order/visibility, Flyer only if listed) and default tab → navigation items; `locations_style` cards/list/grouped; `schedule_style` timeline/by_day/by_location/now_next; `map_clustering` (maps-compose-utils `Clustering`); `map_fit_all` (LatLngBounds on open and on Map-tab reselect); `show_location_images`; `enable_search`; `group_by_category` (filter chips on Locations and Map); `secondary_pane` (beside/below the map on an unfolded foldable); `accent_color` → Material colour scheme `primary`; `logo_url` in the header.

Banners: **Demo Event** when `event.is_demo` (falls back to `!paid` for old servers); **"This event has ended"** when `status == archived`; an offline notice when the last refresh failed.

## Foldables (adaptive layouts)

Foldables are a supported target; tablets are not a goal (layouts simply also work on large screens). No orientation lock.
- **Folded / cover screen (compact width)**: bottom navigation bar; Locations/Schedule show list *or* detail.
- **Unfolded inner display (medium width or more)**: `NavigationSuiteScaffold` switches to a rail; `ListDetailPaneScaffold` shows list + detail side by side; the Map tab shows the map beside `display.secondary_pane`, split exactly on the fold when a vertical hinge is reported (`FoldAwareSplit`).
- **Tabletop posture** (`currentWindowAdaptiveInfo().windowPosture.isTabletop`): map on the top half, pane on the bottom half, split on the horizontal hinge.
- Content respects display cutouts and system bars via `WindowInsets.safeDrawing`.

## Push Notifications (FCM)

`push/AttndMessagingService`:
- `onNewToken` → `POST devices/`.
- data `type=notification` → shows a notification on channel `event_updates` (tapping opens the Notifications tab) and refreshes the bundle.
- data `type=content_updated` → refreshes the bundle (silent).

Notifications are opt-in from **Options**, which requests `POST_NOTIFICATIONS`. The preference is sent as `subscribed` to `POST devices/`.

**Setup** (not in the repo):
1. Create/choose a Firebase project and add Android apps `com.arcu.attnd` **and** `com.arcu.attnd.debug` (the google-services plugin fails a variant whose package is missing).
2. Download `google-services.json` to `app/google-services.json` (git-ignored). In CI, write it from a secret before building.
3. Give the attnd-web backend an FCM HTTP v1 service account (Firebase console → Project settings → Service accounts) so it can send pushes.

Without `google-services.json` the build still succeeds; the plugin isn't applied, Firebase is never initialised, and the app skips all push code paths.

## Deep Linking

- **App Links**: `https://attnd.app/join/<code>/` opens the app (`/.well-known/assetlinks.json` is served by attnd-web). `MainActivity` hands the code to the ViewModel, which looks it up, leaves any current event and joins the new one.
- **First install via QR → Play Store**: on first launch with no event, the Play Install Referrer (`event_code=<code>`) is read once and the app joins directly, skipping the home screen.

## API Key & Secrets Setup

`local.properties` in the project root (not committed):
```properties
ATTND_API_KEY=your_dev_key_here
MAPS_API_KEY=your_maps_key_here
```
Injected into `BuildConfig` / the manifest by `app/build.gradle`.

`signing.properties` for release signing (not committed):
```properties
STORE_FILE=path/to/keystore.jks
STORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

## Version Management

`version.properties`: increment `VERSION_CODE` before each Play Store release. `versionName` is `1.0.<VERSION_CODE>`.

## Building

```bash
./gradlew assembleDebug        # debug
./gradlew assembleRelease      # release (requires signing.properties); or ./build_release.command
```

## Running Tests

```bash
./gradlew test                 # or ./run_tests.command
```
Unit tests are pure JVM (domain + data/api). The live integration tests run only when `ATTND_API_KEY` is set.

## Release

See `RELEASE_SETUP.md` for Play Store upload instructions.

## Related Repos

- **[attnd-web](https://github.com/apbeers/Attnd-Web)** — Django backend + REST API (`API.md` v1, `API_V2.md` v2)
- **[attnd-ios](https://github.com/apbeers/Attnd-iOS)** — SwiftUI iOS client
