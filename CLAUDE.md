# Attnd Android

Kotlin + Jetpack Compose app that lets attendees discover and follow live events. Consumes the Attnd REST API **v2** served by **[attnd-web](https://github.com/apbeers/Attnd-Web)** (contract: [`API_V2.md`](https://github.com/apbeers/Attnd-Web/blob/develop/API_V2.md) in attnd-web).


## Branching (initial development)

- `main` = production (default branch), `develop` = dev/beta. Pushing to `develop` deploys to the dev server / TestFlight / Play internal testing; pushing to `main` deploys to production.
- **While there are no real users yet, commit and push straight to `develop`** — no feature branches or PRs needed. Merge `develop` → `main` to release. Revisit this once the apps have users.


## API changes start in attnd-web

attnd-web is the coordinating repo. The API contract ([`API_V2.md`](https://github.com/apbeers/Attnd-Web/blob/develop/API_V2.md)) and the cross-repo tracker ([`API_CHANGES.md`](https://github.com/apbeers/Attnd-Web/blob/develop/API_CHANGES.md)) live there only; this repo has no copy.
- Don't build against API behaviour that isn't in `API_V2.md`. If a feature needs an API change, add it in attnd-web first (tracker entry, server, contract), then implement the app part here.
- Implement what the tracker entry's app section says. Put its ID in the commit message (e.g. `API-003: …`), then tick this app's column in attnd-web's `API_CHANGES.md`.
- Keep decoding lenient: ignore unknown keys and default missing ones, so additive server changes never break shipped builds.

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
    MainActivity.kt               # Single activity: intents (App Links, notification taps, debug screenshot extras), foreground refresh loop
    domain/                       # Pure Kotlin, unit tested — no Android imports
      Models.kt                   #   Event, EventLocation, ScheduleItem, EventNotification, EventBundle, GeoPoint
      DisplayConfig.kt            #   Server-driven `display` object + client defaults
      EventTime.kt                #   ISO parsing and formatting in the EVENT's time zone
      ScheduleGrouping.kt         #   timeline / by_day / by_location / now_next, search, ended/now
      LocationGrouping.kt         #   category grouping, filters, mappable pins, bounds
      JoinLinks.kt                #   /join/<code>/ App Links + install-referrer parsing
      ScreenshotRequest.kt        #   Debug store-screenshot mode: launch extras → screen to show
      UsageAggregator.kt          #   API-005 counts by (name, detail, UTC 5-minute window) + metric names
      SupportInfo.kt              #   API-004 build/device/event details, support code, copy text
    data/
      net/HttpClient.kt           #   HttpURLConnection client (no caching, so 304s reach us)
      api/AttndApi.kt             #   v2 endpoints; 401 → re-attest → retry once; ETag/If-None-Match
      api/ApiDtos.kt              #   @Serializable wire format (all fields nullable) + ApiJson config
      api/ApiMapper.kt            #   DTO → domain, applying defaults
      EventRepository.kt          #   Single source of truth (StateFlow bundle), join/leave/refresh, disk cache
      UsageReporter.kt            #   API-005 anonymous usage counts: aggregate, flush every ~60 s / background / leave
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
    unit/                         # JVM tests: decoding, display defaults, grouping in event tz, HTTP client, usage, support info
    integration/AttndApiTest.kt   # Live v2 API (skipped when ATTND_API_KEY is not set)
version.properties                # VERSION_CODE — increment for each release
signing.properties                # (not committed) Keystore config for release signing
local.properties                  # (not committed) ATTND_API_KEY, MAPS_API_KEY
app/google-services.json          # (not committed) Firebase config for FCM
```

## Architecture

- **`EventRepository`** owns the joined event. `bundle: StateFlow<EventBundle?>` is the single source of truth; `sync: StateFlow<SyncState>` carries refresh progress and the last error. The raw bundle JSON is written to `filesDir/event_bundle.json` (atomic temp-file rename), so the app starts **offline** with the last content and refreshes in the background.
- **`EventViewModel`** combines repository state with UI-local state into one `AppUiState` (phase LOADING / LAUNCH / EVENT, selected tab, join/nearby state, one-shot focus requests and messages). Screens receive state slices + callbacks only; they hold no app state (only UI state like search text).
- **`UsageReporter`** (in `AppGraph`, runs on `appScope`) collects anonymous usage counts; `EventViewModel` records them with one-line `track(...)` calls, and `EventRepository` flushes it before leaving or switching events.
- Device ID: a UUID generated on first launch (`DevicePrefs.deviceId`), used for join/leave/devices and as `X-Device-Id`. Never sent with usage counts.

### Usage reporting (API-005)

`POST events/<id>/usage/` with `{platform: "android", events: [{name, detail?, count, at}]}`, `at` = UTC start of the 5-minute window (e.g. `2026-11-14T16:05:00Z`). No device id or other identifier, never search text. `UsageReporter` aggregates per event in a `UsageAggregator`, sends at most every 60 s while there is data, when `MainActivity.onStop` runs (not on rotation) and before leave/switch; a failed batch is retried once on the next flush, then dropped; errors are swallowed.

| Metric | Where |
|--------|-------|
| `app_open` | `EventViewModel.foregroundSync` on the first resume after launch or after the app was backgrounded, while in an event |
| `tab_view` (`map`…`notifications`, `options`) | `EventViewModel` init: counted whenever the visible tab (or Options) changes, including the initial tab |
| `location_view` / `schedule_item_view` | `LaunchedEffect` in the Locations/Schedule detail panes and the hidden-tab overlays → `onLocationShown` / `onScheduleItemShown` |
| `notification_open` | `MainActivity` notification-tap intent (`notification_id` extra) → `openNotifications(id)` |
| `search` | `SearchField`: on IME search or once the non-empty query settles for 1.5 s → `onSearch` |
| `refresh` | `EventViewModel.refresh` (pull to refresh only) |

### Support info (API-004)

Options shows an "Internal test build" card when `BuildConfig.ATTND_SERVER != "attnd.app"`, and an **About & support** section from `AppUiState.supportInfo` (built in `EventViewModel`): version/code, build type, server, API version, Android version, device, event name/code/id/content version, last refresh (device time zone), the **support code** (first 8 characters of the device id, no dashes, upper case) and **Copy support info** (plain text including the full device id, via `LocalClipboard`).

## API (v2)

Base `https://attnd.app/api/v2/` — see attnd-web `API_V2.md`.

| Call | Use |
|------|-----|
| `GET events/nearby/?lat&lng` | Launch screen "Find nearby events" |
| `GET events/lookup/?q=` | Event code / exact name (QR codes, App Links, referrer) |
| `GET events/<id>/bundle/` | Everything shown; `If-None-Match` → `304`; `X-Device-Id` header |
| `POST events/<id>/join/` | `{device_id, platform, push_token, app_version}` |
| `POST events/<id>/leave/` | `{device_id}` |
| `POST devices/` | On launch and when push token / notification preference changes |

Bundle refresh: on join, when the app is resumed, pull-to-refresh, on a `content_updated` push, and a **live poll every 5 s** while the activity is resumed and an event is joined (`EventViewModel.foregroundSync`; conditional GET, usually `304`) so organizer edits show up live. Polls use `refresh(silent = true)`: no spinner, and the offline notice only appears after 3 failed polls in a row (any success clears it). A `404` on the bundle clears the event and returns to the launch screen.

Models tolerate missing and unknown keys everywhere. Coordinates may be `null`: never pin those, and never treat `0,0` as real (`GeoPoint.of`). Schedule items may have no location ("General"). All times are shown in the event's `timezone`.

## Authentication

`Authorization: Bearer <token>`:
1. **Play Integrity JWT** — from `/api/v1/attest/android/` after verifying a Play Integrity token (production).
2. **`BuildConfig.ATTND_API_KEY`** — static fallback for dev/debug/emulator builds, from `local.properties` or the `ATTND_API_KEY` environment variable.

On a 401 the stored JWT is invalidated and the request is retried once after re-attesting (`AttndApi.send`). After an attestation failure, attestation is not retried for 10 minutes.

## Server-driven Display

`DisplayConfig` resolves the bundle's `display` object with the documented defaults:
tabs (order/visibility, Flyer only if listed) and default tab → navigation items; `locations_style` cards/list/grouped; `schedule_style` timeline/by_day/by_location/now_next; `map_clustering` (maps-compose-utils `Clustering`); `map_fit_all` (LatLngBounds on open and on Map-tab reselect); `show_location_images`; `enable_search`; `group_by_category` (filter chips on Locations and Map); `secondary_pane` (beside/below the map on an unfolded foldable); `accent_color` → Material colour scheme `primary` (otherwise the brand purple: `#431F86` light / `#9D7BEA` dark, `ui/theme/Color.kt` + `res/values/colors.xml`; no Material You dynamic colour); `logo_url` in the header.

Status: a small floating pill (`EventStatusPills`, overlaid top-centre of the tab content, takes no layout space) shows **Demo event** when `event.is_demo` (falls back to `!paid` for old servers) and **Event ended** when `status == archived`; a slim offline notice row (`OfflineNotice`) sits above the content while `SyncState.error` is set.

## Foldables (adaptive layouts)

**Phones and foldables only; no tablets.** Tablets are excluded from distribution in Play Console's device catalog (manifest settings can't do it without also excluding unfolded foldables). Don't add tablet-specific layouts; the adaptive layouts below exist for unfolded foldables and simply also work on any large window. No orientation lock (Android 16+ ignores it on large screens anyway).
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

CI sets `VERSION_CODE=<run number>000` and `VERSION_NAME=2.0.<run number>` (always increasing, above the last manual build 224). `version.properties` is only used for local builds.

## CI/CD (`.github/workflows/android.yml`)

| Trigger | What happens |
|---------|--------------|
| Pull request | `testDebugUnitTest lintDebug assembleDebug` on ubuntu-latest (JDK 21; SDK packages installed by AGP) |
| Push to `develop` | One job: `testDebugUnitTest lintDebug bundleRelease` in a single Gradle run (parallel), signed against the `dev` environment's `ATTND_SERVER` (Heroku dev app) → Play **internal testing** |
| Push to `main` | The same against `attnd.app` → Play **production** (staged rollout if `PLAY_ROLLOUT` < 1), plus the store listing text in the same edit |

Docs-only commits (`*.md`) don't run the pipeline; a newer push to `develop` cancels the older run (`main` never cancels). Gradle: parallel, build cache, configuration cache, 4 GB heap (`gradle.properties`). Publishing uses `scripts/ci/play_publish.py` (Play Developer API v3, service-account JWT) — no third-party actions or Gradle plugins. Release builds never contain the static API key. Server host: `ATTND_SERVER` → `BuildConfig.ATTND_SERVER` (default `attnd.app`). Setup checklist: `SETUP.md` in attnd-web.

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

## Store listing (`store-text.yml`, `store-screenshots.yml`)

The Play listing is generated, not edited in Play Console. Text lives in `store/en-US/`: `title.txt` (≤ 30), `short_description.txt` (≤ 80), `full_description.txt` (≤ 4000), `release_notes.txt` (≤ 500, used by `android.yml` releases unless the `RELEASE_NOTES` repo variable is set) and `captions.txt` (`screen=caption`, one screenshot per line in listing order, 2–8 lines, two short lines of text at most).

Two **manual-only** workflows (they never run with normal builds), plus the release:

| Workflow | What it does | Time |
|----------|--------------|------|
| `store-text.yml` ("Store listing text") | Validates `store/en-US` text; with **upload** (default on) replaces the en-US title/descriptions on Play and commits | ~1 min, Linux |
| `store-screenshots.yml` ("Store screenshots") | Emulator screenshots + feature graphic + icon → `store-listing` artifact; with **upload** (default off) replaces the Play images only | ~20–30 min |
| `android.yml` on `main` | The production release also publishes the listing text, in the same Play edit | — |

- **When the user asks Claude to "update the store listing / screenshots"**: dispatch the workflow(s) on ref `main` with the GitHub MCP `actions_run_trigger` (`run_workflow`, `workflow_id` `store-text.yml` / `store-screenshots.yml`, inputs `upload: true`), follow the run, download the `store-listing` artifact and show the user the images. Listing and image uploads go live for everyone after Google's review — confirm with the user before uploading if they only asked for a preview. Manual runs need the workflow file on the default branch (`main`).
- **Title policy** ([Play metadata guidance](https://android-developers.googleblog.com/2021/04/updated-guidance-to-improve-your-app.html)): the store title is "Attnd: Event Maps & Schedules"; the launcher label (`app_name`) stays "Attnd". No emoji, ALL CAPS, ranking/price/promotional words ("best", "#1", "free", "new", "sale", …) or keyword stuffing anywhere in the listing. `play_publish.py --listing store/en-US --check` enforces lengths and these rules offline; run it after editing the text.
- **Screenshot mode** (debug builds only, `domain/ScreenshotRequest.kt`): `adb shell am start -n com.arcu.attnd.debug/com.arcu.attnd.MainActivity --es attnd_screenshot <launch|map|locations|location|schedule|notifications>` (optional `attnd_screenshot_event`, default `DEMO`; `attnd_screenshot_location`, default "Main Stage"). It joins the event through the normal lookup/join path and shows that screen with no launch screen flash, update banner/screen, Demo pill, offline notice, snackbar or usage counts; the map shot fits every pin without clustering. Keep it working when changing navigation or join.
- **Screenshot pipeline**: builds the debug APK against the dev server (`ATTND_API_KEY` repo secret, `MAPS_API_KEY`) with `-PstoreScreenshots`: package `com.arcu.attnd`, signed with the upload key (`ANDROID_KEYSTORE_*`), because the Maps key only accepts the real app's package and certificates (otherwise the map is blank); the APK never leaves the emulator. It boots a headless `android-37.0;google_apis;x86_64` emulator (Pixel 9 Pro XL profile, KVM, emulator system libraries such as libpulse installed by the script, demo-mode status bar at 9:41) and captures `build/screenshots/raw/<screen>.png` (`screenshots.sh`); `compose_screenshots.py` makes 2160×3840 phone screenshots (purple gradient, caption, framed screenshot), the 1024×500 feature graphic and the 512×512 icon (from `drawable/attnd_logo.png`) in `build/store/`.
- **`play_publish.py`**: `--listing DIR` (text), `--images DIR` (+ `--images-only`), or an `.aab` with `--listing` (release + text in one edit). A commit withdraws anything already in review and resubmits it together. Use `--not-for-review` if Play requires changes to be sent from the Console (after a rejection).
- Python for CI scripts runs in a venv (Pillow, `pyjwt[crypto]`); `__pycache__/` is git-ignored.

## Release

See `RELEASE_SETUP.md` for Play Store upload instructions.

## Related Repos

- **[attnd-web](https://github.com/apbeers/Attnd-Web)** — Django backend + REST API, and the **coordinating repo**: `API_V2.md` (contract), `API_CHANGES.md` (tracker)
- **[attnd-ios](https://github.com/apbeers/Attnd-iOS)** — SwiftUI iOS client
