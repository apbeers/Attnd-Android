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

- **Kotlin** with **Jetpack Compose**, **Material 3 Expressive** (`androidx.compose.material3` **1.5.0-rc01**, pinned above the BOM's stable 1.4.0, which has no Expressive APIs: `MaterialExpressiveTheme` + `MotionScheme.expressive()`, `TopAppBar`, `FloatingActionButton` + `ExpandedFullScreenSearchBar` + `SearchBarState`, `ShortNavigationBar` / `WideNavigationRail` via `NavigationSuiteScaffold`, `VerticalFloatingToolbar`, `ButtonGroup`, connected `ToggleButton`s, `LoadingIndicator` / `ContainedLoadingIndicator`, `LinearWavyProgressIndicator`, the `ListItem(content = …)` overloads) and **Material3 adaptive** (`NavigationSuiteScaffold` 1.5.0-rc01, `ListDetailPaneScaffold`, `currentWindowAdaptiveInfoV2`). Return material3 to the BOM once a BOM ships 1.5.0
- **AndroidX AppFunctions** `1.0.0-rc01` (+ KSP `com.google.devtools.ksp` 2.3.13 for its compiler) — assistants (Gemini) on Android 16+, see Assistant
- **ML Kit GenAI Prompt API** `genai-prompt` `1.0.0-beta5` (**beta**) — on-device Gemini Nano for "Ask about this event", runtime-checked
- **Networking**: `HttpURLConnection` + coroutines (`data/net/HttpClient.kt`)
- **JSON**: kotlinx.serialization (`ignoreUnknownKeys`, every field defaulted)
- **Images**: in-house loader (`ui/components/RemoteImage.kt`: memory LruCache → disk `data/ImageDiskCache.kt` in `filesDir/images/` → HttpURLConnection; BitmapFactory decode)
- **AndroidX lifecycle** ViewModel + `collectAsStateWithLifecycle`
- **Google Maps**: `maps-compose` + `maps-compose-utils` (googlemaps first-party; marker clustering, `MarkerComposable` pins), Play Services location (fused location + `FusedOrientationProviderClient` compass)
- **Google Routes API v2** `computeRoutes` (WALK) over HttpURLConnection for walking routes on the map (`data/WalkRouter.kt`; same `MAPS_API_KEY`, see Glance)
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
      ImageCachePlan.kt           #   API-007 image cache: file names, referenced URLs, stale files, budget
      LiveUpdates.kt              #   API-007 poll interval, push refresh rules + jitter, offline notice text
      SearchText.kt               #   Search normalisation (lower-case, accents folded, punctuation → spaces) with index mapping
      SearchQuery.kt              #   Query parsing: tokens + time words (now/next/tonight/days/clock times), keywords(); TimeMatcher
      EventSearch.kt              #   Local search over the bundle: scoring, ranking, result detail lines, suggestions
      EventFacts.kt               #   Structured items for AppFunctions (search, now/next, updates, event info)
      AssistantPrompt.kt          #   On-device assistant: context retrieval, prompt, [S12]-style citations
      OpenLinks.kt                #   attnd-open://<event>/<target> internal links (shortcuts, AppFunctions results)
      RelativeTime.kt             #   "in 12 min" / "Started 5 min ago" / "tomorrow 7:30 PM" / "in 2 weeks" (event tz)
      Geo.kt                      #   Distance text (region units), haversine, bearing, compass points, arrow rules
      Walking.kt                  #   Walk estimate, route plausibility, walk texts; EncodedPolyline decoder
      Glance.kt                   #   Now/next per location, pin subtitles, Now playing, progress; UnreadUpdates
      Hearts.kt                   #   Hearted ids per event (encode/decode), API-009 metric names
    data/
      net/HttpClient.kt           #   HttpURLConnection client (no caching, so 304s reach us)
      api/AttndApi.kt             #   v2 endpoints; 401 → re-attest → retry once; ETag/If-None-Match
      api/ApiDtos.kt              #   @Serializable wire format (all fields nullable) + ApiJson config
      api/ApiMapper.kt            #   DTO → domain, applying defaults
      EventRepository.kt          #   Single source of truth (StateFlow bundle), join/leave/refresh, disk cache, push token state
      ImageDiskCache.kt           #   API-007 offline images (filesDir/images/): fetch, prefetch, clean-up
      OnDeviceAssistant.kt        #   ML Kit GenAI Prompt API wrapper: availability, model download, answer (guarded)
      UsageReporter.kt            #   API-005 anonymous usage counts: aggregate, flush every ~60 s / background / leave
      AttestedTokenProvider.kt    #   Bearer token: attestation JWT or static key
      InstallReferrer.kt          #   Play Install Referrer → event_code
      attest/AttestationApi.kt    #   Unauthenticated /api/v1/attest/ endpoints
      prefs/DevicePrefs.kt        #   device id, joined event id/code, ETag, push token, preferences, per-event hearts / updates seen / banner dismissed
      HeartStore.kt               #   Hearts per event (prefs + version flow; UI and AppFunctions)
      DeviceSensors.kt            #   UserLocationSource (fused location) + HeadingSource (compass), ref-counted, only while visible
      WalkRouter.kt               #   Routes API computeRoutes + per-location cache; AppSignature (signing cert SHA-1)
    assistant/                    # AppFunctions service (KSP generates AttndAppFunctionService), dynamic shortcuts, open intents
    push/                         # FCM service + notification channel / display
    ui/
      EventViewModel.kt           #   AppUiState + all user actions
      AttndApp.kt                 #   Root composable, theme (accent colour), EventShell with NavigationSuiteScaffold
      MapTab.kt                   #   Foldable-aware map + secondary pane
      components/                 #   RemoteImage, FoldAwareSplit, IdListDetail, banners, chips, QR scanner; Glance.kt (DistanceArrow, WalkTime, HeartButton, PeekSheet, UpdateBanner, LocalEventActions, LocalScreenshotMode)
      screens/                    #   Launch, Map, Locations, Schedule, Notifications, Flyer, Options, Search (app-wide search + Ask)
      theme/
  src/test/java/com/arcu/attnd/
    unit/                         # JVM tests: decoding, display defaults, grouping in event tz, HTTP client, usage, support info
    integration/                  # Live v2 API tests (AttndApiTest protocol, DemoEventTest DEMO content; LiveServer config), only with -PintegrationTests
version.properties                # VERSION_CODE — increment for each release
signing.properties                # (not committed) Keystore config for release signing
local.properties                  # (not committed) ATTND_API_KEY, MAPS_API_KEY
app/google-services.json          # (not committed) Firebase config for FCM
```

## Architecture

- **`EventRepository`** owns the joined event. `bundle: StateFlow<EventBundle?>` is the single source of truth; `sync: StateFlow<SyncState>` carries refresh progress and the last error. The raw bundle JSON is written to `filesDir/event_bundle.json` (atomic temp-file rename), so the app starts **offline** with the last content and refreshes in the background.
- **Offline images (API-007)**: `ImageDiskCache` (in `AppGraph`, on `appScope`) keeps the bundle's images in `filesDir/images/<sha256(url)>` (not the cache dir, so the OS doesn't purge them mid-event; excluded from backup by `res/xml/data_extraction_rules.xml`). `ImageLoader` reads memory → disk → network; downloads go to a temp file and are renamed into place. After every applied bundle, after `restore()` and on a `304` (no-op once complete) the repository prefetches `ImageCachePlan.referencedUrls` (header/event logo, flyer unless it is a PDF, location images) 4 at a time; once every image has been attempted, files the bundle no longer references are deleted (new images first, a failed download keeps the old file). Budget ~300 MB per event, files > 15 MB are never stored (shown from memory). Leaving/switching events deletes them with the bundle. URLs are treated as immutable (an existing file is never re-downloaded). A PDF flyer is not cached (no FileProvider; it opens the URL in the system viewer).
- **`EventViewModel`** combines repository state with UI-local state into one `AppUiState` (phase LOADING / LAUNCH / EVENT, selected tab, join/nearby state, one-shot focus requests and messages). Screens receive state slices + callbacks only; they hold no app state (only UI state like search text).
- **`UsageReporter`** (in `AppGraph`, runs on `appScope`) collects anonymous usage counts; `EventViewModel` records them with one-line `track(...)` calls, and `EventRepository` flushes it before leaving or switching events.
- Device ID: a UUID generated on first launch (`DevicePrefs.deviceId`), used for join/leave/devices and as `X-Device-Id`. Never sent with usage counts.

### Usage reporting (API-005)

`POST events/<id>/usage/` with `{platform: "android", events: [{name, detail?, count, at}]}`, `at` = UTC start of the 5-minute window (e.g. `2026-11-14T16:05:00Z`). No device id or other identifier, never search text. `UsageReporter` aggregates per event in a `UsageAggregator`, sends at most every 60 s while there is data, when `MainActivity.onStop` runs (not on rotation) and before leave/switch; a failed batch is retried once on the next flush, then dropped; errors are swallowed.

| Metric | Where |
|--------|-------|
| `app_open` | `EventViewModel.foregroundSync` on the first resume after launch or after the app was backgrounded, while in an event |
| `tab_view` (`map`…`notifications`, `options`) | `EventViewModel` init: counted whenever the visible tab (or Options) changes, including the initial tab |
| `location_view` / `schedule_item_view` | `LaunchedEffect` in the Locations/Schedule detail panes, the search screen's details and the hidden-tab overlays → `onLocationShown` / `onScheduleItemShown` |
| `notification_open` | `MainActivity` notification-tap intent (`notification_id` extra) → `openNotifications(id)` |
| `search` | `SearchField` and the app-wide search / Ask (`rememberSearchReporter`): on IME search or once the non-empty query settles for 1.5 s → `onSearch` |
| `refresh` | `EventViewModel.refresh` (pull to refresh only) |
| `location_heart` / `location_unheart`, `schedule_item_heart` / `schedule_item_unheart` (API-009, detail = id) | `EventViewModel.toggleHeart` and AppFunctions `setHeart`, only when the heart actually changes (never on restore, rejoin or leave) |

### Support info (API-004)

Options shows an "Internal test build" card when `BuildConfig.ATTND_SERVER != "attnd.app"`, and an **About & support** section from `AppUiState.supportInfo` (built in `EventViewModel`): version/code, build type, server, API version, Android version, device, event name/code/id/content version, last refresh (device time zone), the **support code** (first 8 characters of the device id, no dashes, upper case) and **Copy support info** (plain text including the full device id, via `LocalClipboard`).

## Search (local, app-wide)

Shared spec with iOS (same rules and the same unit-test cases, `unit/EventSearchTest.kt`, fixed `now`, America/Chicago). No server call; it searches the bundle on disk, so it works offline.
- **Entry point**: a Material 3 search **FAB** (`SearchFab`, search icon, content description "Search") on every tab opens the full-screen search, an `ExpandedFullScreenSearchBar` (`EventSearch` in `ui/screens/SearchScreen.kt`; the `SearchBarState` lives in `EventShell`, `animateToExpanded()`). Where the FAB sits: in the wide navigation rail's header when the rail is shown (`NavigationSuiteScaffold(primaryActionContent = …)`); on the Map tab otherwise as the FAB of the map's `VerticalFloatingToolbar` (`floatingActionButton`, so the two never overlap); on the other tabs in the inner `Scaffold`'s `floatingActionButton` slot (bottom-end, above the navigation bar, insets respected, snackbars above it). Opening focuses the field and shows the keyboard (`FocusFieldOnOpen`: waits for the search dialog's window focus, then `FocusRequester.requestFocus()` + `keyboard.show()`; opened straight onto a detail by a request, the keyboard stays hidden). Back closes it (a detail first pops back to the results). The event's top bar is a plain `TopAppBar` (`EventTopBar`: event logo, event name, Options action; Options is never a navigation item). Empty query → suggestion chips ("Happening now", "Up next", categories).
- **Matching** (`domain/EventSearch.kt`, `SearchQuery.kt`, `SearchText.kt`): both sides normalised; every token must match (AND across fields); a word equal to the token (4), starting with it (3), containing it (2) or, for 5+ character tokens, Levenshtein ≤ 1 (1; a transposition is 2 edits). Weights: names 3; tags (schedule items and locations, API-010), location name, category, snippet, address, event code 2; descriptions and notification bodies 1. Schedule items without a location are searchable as "General". Boosts: now +8, next +4, ended −1000 (always last). Ties: kind (schedule, location, notification, event), title, id.
- **Time words** in the event's time zone: `now`/`happening now`, `next`/`up next`/`soon` (2 h), `today`, `tonight` (starts ≥ 17:00 today), `tomorrow`, weekdays (`sat` only when no content word is "sat"), clock times `3pm`, `3 pm`, `15:00`, `3:30` (both 03:30 and 15:30), `noon` (in progress then, or starting ≤ 30 min after, on the event's days). A bare `7` is not a time. Time words filter only schedule items; a time-only query returns only schedule items. The search field never drops stop words; `SearchQuery.keywords()` (stop words removed) is only for questions (assistant, AppFunctions).
- **Results**: kind icon, title with the match in bold primary, one detail line (`EventSearch.detail`: "Sat 7:30–8:30 PM · Main Stage", "Food · Street tacos", "Sat 3:55 PM · body…", "Nov 14–15 · address"; absolute times, never relative), Now/Next/Ended label, distance + arrow for locations, a heart for locations and schedule items.
- **Hearted**: once anything is hearted, the empty-query suggestions start with a **Hearted** chip that opens the hearted list (`EventSearch.hearted`: hearted schedule items in time order, then hearted locations in the organizer's order); it is not a query word.
- **Navigation**: a tapped result opens its detail on the search screen's own back stack (`SearchEntry`; Back returns to the results), so items in tabs that `display.tabs` hides still open; location ↔ schedule links push further entries. External requests (shortcuts, AppFunctions `openInApp`) open the item's own tab when it is visible (notifications scroll to and briefly highlight the item), otherwise open via the search screen (`SearchRequest.detail`).
- **Usage**: the existing `search` metric only, counted exactly like the per-tab fields (`rememberSearchReporter`: IME search or a non-empty query settled for 1.5 s; never the text). No `tab_view` for search; detail views count `location_view` / `schedule_item_view` as elsewhere.

## Glance, distances, walking routes, hearts (shared spec with iOS, wave 1)

Everything is on the device; pure rules in `domain/` with JVM tests (`RelativeTimeTest`, `GeoTest`, `WalkingTest`, `GlanceTest`, `HeartsTest`; fixed now Sat Nov 14 2026 2:00 PM America/Chicago, en_US; same decisions as iOS).
- **Relative time** (`RelativeTime`): no end / an end not after the start = start + 1 h; ending exactly now is "Ended"; "Started just now" (< 1 min), "Started 20 min ago", "Now" (long form adds " · ends 8:30 PM", "· ends Sun 1:00 AM" on another day); "Starting now", "in 12 min", "in 3 h 20 min" (< 6 h), "today 7:30 PM", "tomorrow 7:30 PM", "in 3 days", "in 1 week" (days/7, < 5 weeks), calendar months/years (≥ 1). Minutes round down. Used by schedule rows (updates every 30 s with `rememberNow`), schedule detail (long form), location schedules, peek and the launch screen's nearby events (`forNearby`: the event's start, else `starts_in_hours` turned into a date; without an end it lasts until the end of its first day). Search subtitles keep absolute times.
- **Distance** (`DistanceFormat`, `DistanceUnits.forRegion`): US/GB/LR/MM → feet under 0.1 mi (nearest 10, min 10), miles with one decimal under 10, whole miles; elsewhere metres under 1 km (nearest 10), km with one decimal under 10. Locale decimal separator. Spoken: feet/miles/meters/kilometers. Haversine, R = 6,371,000 m. "Here" under 20 m.
- **Arrow** (`DistanceArrow` in `ui/components/Glance.kt`, `Geo.direction`): bearing − heading (true north), animated the short way; hidden without a heading, with negative heading accuracy, with approximate location only, or a fix worse than 100 m (`Geo.arrowAllowed`); content description "Northeast, 0.6 miles" (8 points centred on 0°, 45°, …). Shown on nearby events, location rows/cards/detail, the map card, location search results, schedule detail. `HeadingSource` uses `FusedOrientationProviderClient` (play-services-location 21.4.0, 200 ms) and falls back to the rotation-vector sensor (+ declination); both are display-rotation corrected and run only while a composable showing an arrow is visible and resumed (`rememberHeading`, `LifecycleResumeEffect`, reference counted). `UserLocationSource` (fused, high accuracy, 5 s) likewise (`rememberUserLocation`); nothing without the location permission.
- **Walking** (`Walking`): estimate = straight × 1.2 ÷ 1.3 m/s, minutes rounded up (min 1, "1 h 6 min"); only within 8,000 m. Rows (schedule rows, detail, peek): `WalkTime` → "8 min walk" or "You're here" under 20 m. Map: tapping a pin selects it (no info window) and shows `SelectedLocationCard` (now/next, distance + arrow, heart, Details, Directions → Google Maps; the app never navigates) and, within 8 km with permission, a route: `WalkRouter` POSTs `https://routes.googleapis.com/directions/v2:computeRoutes` (`travelMode: WALK`) with `X-Goog-Api-Key` = `MAPS_API_KEY`, `X-Goog-FieldMask: routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline`, `X-Android-Package` and `X-Android-Cert` (SHA-1 of the current signing certificate, upper-case hex without colons, `AppSignature` via `PackageManager` signing info); `EncodedPolyline.decode` is our own. A route longer than 2.5 × the straight line, no route, offline or any error (e.g. a debug build the key's app restriction rejects) → the estimate as a dashed straight line, "about 8 min walk"; a route reads "12 min walk · 0.6 mi". One result is cached per location and reused while the user is within 50 m of where it was computed; failures aren't cached. The Routes API must be enabled for the key (attnd-web `SETUP.md`).
- **Glanceables** (`Glance`): pins (`MarkerComposable` / `clusterItemContent`) get a live dot when something is in progress there and "Next 7:30" (no AM/PM) when the next item starts within 3 h; a **Now playing** strip under the map chips ("Main Stage · Prairie Lights", items in progress at locations with coordinates, by start, location order, id; tap → select); location rows/cards/detail show "Now: X · Next 8:30 Y" ("Next Sun 10:00 Y" on another day; now = the earliest-starting item in progress); schedule rows show the relative time, a thin progress bar while in progress and the walk time. **Peek**: long-press a location or schedule row (`ListItem(onLongClick)`, cards `combinedClickable`) → `PeekSheet` (`ModalBottomSheet`: key info, Heart, Show on map, Open). **Updates**: the Updates navigation item has an unread badge (`UnreadUpdates`: updates with a send time after the tab was last seen, every dated one before it was ever seen; seen = max(now, newest send time) while the tab is visible; per event in `DevicePrefs`), and the newest unread update sent within 24 h is a dismissible `UpdateBanner` above the other tabs (dismissed id per event).
- **Hearts** (`Hearts`, `HeartStore`, `HeartButton`): on schedule items and locations in rows, cards, details, the map card, search results and the peek; stored per event in `DevicePrefs` (SharedPreferences, included in Auto Backup) and kept after leaving. A **Hearted** filter chip (only once something of that kind is hearted) on Schedule, Locations and the Map (hearted pins only); the search screen's Hearted entry. AppFunctions `getHeartedItems` and `setHeart(kind, id, hearted)`. `EventActions` (`LocalEventActions`, provided in `AttndApp` around `EventShell`) carries hearts, the toggle and "Show on map" (`EventViewModel.showOnMap` → `mapFocusLocationId`) so rows don't thread them through every list.
- **Tags** (API-010) arrive lower case (`EventLocation.tags`, `ScheduleItem.tags`): filter and search them as sent; `Tags.display` capitalises for display only ("Live music · Free"). **Urgent** notifications (API-011, `EventNotification.priority`, unknown → normal) are highlighted in the Updates list (error container, "Urgent", warning icon), the update banner and the search detail. (The urgent FCM channel and Live Update are wave 2.)
- **Screenshot mode** (`LocalScreenshotMode`): no location tracking (no distances, arrows, walk times, routes), no map glance features (live dots, "Next" labels, Now playing), no unread badge or update banner.

## Assistant (Gemini, on-device AI)

Everything is local: no server or API change, and the query only ever goes to the on-device model.
- **AppFunctions** (`assistant/AttndAppFunctions.kt`, AndroidX `appfunctions` 1.0.0-rc01, Android 16+ only): `BaseAttndAppFunctionService` (`@AppFunctionServiceEntryPoint`; KSP generates `AttndAppFunctionService` and `assets/attnd_app_functions.xml`) exposes `searchEvent`, `getHappeningNow`, `findScheduleItems`, `findLocations`, `getLatestUpdates`, `getEventInfo`, `getHeartedItems`, `setHeart`, returning `AttndEventItem`s (`@AppFunctionSerializable`; ISO times with the event's offset, place, coordinates) built by `domain/EventFacts.kt` from the bundle on disk (`repository.restore()`, works while the app isn't running), each with an `openInApp` `PendingIntent`. The manifest service is enabled only on API 36+ (`@bool/app_functions_enabled`, `values-v36`); app-level guidance for agents is `res/xml/app_functions_metadata.xml`. As of Nov 2026 Gemini's AppFunctions integration is a private preview; test with `adb shell cmd app_function list-app-functions --package com.arcu.attnd.debug`.
- **Open links**: `attnd-open://<event id|any>/<location/5|schedule/3|update/9|updates|event|search?q=…>` (`domain/OpenLinks.kt`) as the data of explicit intents to MainActivity (`assistant/OpenIntents.kt`; no intent filter declares the scheme). A link for another event than the joined one is ignored.
- **Dynamic shortcuts** (`assistant/AppShortcuts.kt`, `ShortcutManagerCompat`): Search, Happening now, Updates while an event is joined; removed without one. App Actions `shortcuts.xml` capabilities are not used (they target Google Assistant; Gemini uses AppFunctions).
- **Ask about this event** (search screen, Search | Ask toggle): Gemini Nano via ML Kit GenAI Prompt API **beta** (`data/OnDeviceAssistant.kt`). Shown only when `checkStatus()` says AVAILABLE, DOWNLOADABLE (Download button) or DOWNLOADING (progress); otherwise plain search. `AssistantPrompt` retrieves context locally (keywords, any-token matches + now/next, ≤ 12 items), asks for ≤ 3 sentences citing `[S12]`/`[L5]`/`[U9]`/`[E]`, and the cited items (or the context) become tappable results; on any error the matches are shown instead. Never in screenshot mode.

## API (v2)

Base `https://attnd.app/api/v2/` — see attnd-web `API_V2.md`.

| Call | Use |
|------|-----|
| `GET events/nearby/?lat&lng` | Launch screen "Find nearby events" |
| `GET events/lookup/?q=` | Event code / exact name (QR codes, App Links, referrer) |
| `GET events/<id>/bundle/` | Everything shown; `If-None-Match` → `304`; `X-Device-Id` header |
| `POST events/<id>/join/` | `{device_id, platform, push_token, app_version}` |
| `POST events/<id>/leave/` | `{device_id}` |
| `POST devices/` | On launch and when push token / notification preference changes; `push_token` whenever there is one (omitted, never `""`, without one), `subscribed` = the opt-in |

Bundle refresh (API-007): live updates are **push-driven**. A `content_updated` push refreshes silently after a random 0–10 s delay, only when its `content_version` is newer than the bundle's (`EventRepository.onContentUpdated`, works in the background too). Also on join, when the app is resumed, on pull-to-refresh, and a poll while the activity is resumed and an event is joined (`EventViewModel.foregroundSync`; conditional GET, usually `304`): a **5-minute safety poll** when the server has this device's push token (`EventRepository.hasPushToken`), otherwise **every 60 s** (no Firebase config, no Play services, token fetch or registration failed) — `LiveUpdates.pollIntervalMs`. Polls and push refreshes use `refresh(silent = true)` (no spinner); at these intervals the offline notice appears after the first failed poll (`LiveUpdates.failuresBeforeOfflineNotice`; any success clears it) and reads "Offline · last updated 2:14 PM" (last successful refresh, device time zone and 12/24-hour setting). A `404` on the bundle clears the event and returns to the launch screen.

Models tolerate missing and unknown keys everywhere. Coordinates may be `null`: never pin those, and never treat `0,0` as real (`GeoPoint.of`). Schedule items may have no location ("General"). All times are shown in the event's `timezone`.

## Authentication

`Authorization: Bearer <token>`:
1. **Play Integrity JWT** — from `/api/v1/attest/android/` after verifying a Play Integrity token (production).
2. **`BuildConfig.ATTND_API_KEY`** — static fallback for dev/debug/emulator builds, from `local.properties` or the `ATTND_API_KEY` environment variable.

On a 401 the stored JWT is invalidated and the request is retried once after re-attesting (`AttndApi.send`). After an attestation failure, attestation is not retried for 10 minutes.

## Server-driven Display

`DisplayConfig` resolves the bundle's `display` object with the documented defaults:
tabs (order/visibility, Flyer only if listed; at most `DisplayConfig.MAX_TABS` = 5 in the listed order, never a "more" item; icons Map `Map`, Locations `List`, Schedule `CalendarMonth`, Flyer `Description`, Updates `Notifications`, all distinct) and default tab → navigation items; `locations_style` cards/list/grouped; `schedule_style` timeline/by_day/by_location/now_next; `map_clustering` (maps-compose-utils `Clustering`); `map_fit_all` (LatLngBounds on open and on Map-tab reselect); `show_location_images`; `enable_search`; `group_by_category` (filter chips on Locations and Map); `secondary_pane` (beside/below the map on an unfolded foldable); `accent_color` → Material colour scheme `primary` (otherwise the brand purple: `#431F86` light / `#9D7BEA` dark, `ui/theme/Color.kt` + `res/values/colors.xml`; no Material You dynamic colour; `MaterialExpressiveTheme` on `expressiveLightColorScheme()` / `darkColorScheme()`); `logo_url` at the start of the top app bar. Pull to refresh uses the Expressive loading indicator (`RefreshBox`), the Map's controls a vertical floating toolbar (with the search FAB below it when the navigation is a bar or absent), location detail actions a button group.

Status: a small floating pill (`EventStatusPills`, overlaid top-centre of the tab content, takes no layout space) shows **Demo event** when `event.is_demo` (falls back to `!paid` for old servers) and **Event ended** when `status == archived`; a slim offline notice row (`OfflineNotice`: "Offline · last updated 2:14 PM", from `SyncState.offline` / `lastSyncedAt`) sits above the content while `SyncState.error` is set.

## Foldables (adaptive layouts)

**Phones and foldables only; no tablets.** Tablets are excluded from distribution in Play Console's device catalog (manifest settings can't do it without also excluding unfolded foldables). Don't add tablet-specific layouts; the adaptive layouts below exist for unfolded foldables and simply also work on any large window. No orientation lock (Android 16+ ignores it on large screens anyway).
- **Folded / cover screen (compact width)**: Material 3 Expressive short navigation bar (`NavigationSuiteScaffoldDefaults.navigationSuiteType`; horizontal items when the height is compact or in tabletop posture); Locations/Schedule show list *or* detail.
- **Unfolded inner display (medium width or more)**: `NavigationSuiteScaffold` switches to a collapsed wide navigation rail (the search FAB in its header); `ListDetailPaneScaffold` shows list + detail side by side; the Map tab shows the map beside `display.secondary_pane`, split exactly on the fold when a vertical hinge is reported (`FoldAwareSplit`).
- **Tabletop posture** (`currentWindowAdaptiveInfoV2().windowPosture.isTabletop`): map on the top half, pane on the bottom half, split on the horizontal hinge.
- Content respects display cutouts and system bars via `WindowInsets.safeDrawing`.

## Push Notifications (FCM)

`push/AttndMessagingService`:
- `onNewToken` → `POST devices/`.
- data `type=notification` → shows a notification on channel `event_updates` (tapping opens the Notifications tab) and refreshes the bundle (silent).
- data `type=content_updated` (`event_id`, `content_version`; FCM data values are strings) → `EventRepository.onContentUpdated`: silent refresh after a random 0–10 s delay if the version is newer (API-007). The server sends it to **every** device in the event with a token, opted in or not (debounced, collapsible, normal priority).

The FCM token is always fetched and sent with `POST devices/` at launch (and with join) when Firebase is configured, whatever the opt-in; data messages need no permission. Visible notifications are opt-in from **Options**, which requests `POST_NOTIFICATIONS`; the preference is sent as `subscribed`. A token the server hasn't confirmed is re-sent from the foreground poll loop.

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
| Every run | In parallel: **Unit tests & lint** (`testDebugUnitTest lintDebug`, plus `assembleDebug` on pull requests; then `scripts/ci/lint_summary.py` prints lint's findings as warnings, never failing), **Integration · dev** (`dev.attnd.app`, secret `ATTND_API_KEY`) and **Integration · prod** (`attnd.app`, secret `ATTND_PROD_CI_KEY`, accepted for the DEMO event only): `testDebugUnitTest -PintegrationTests`, then `scripts/ci/junit_summary.py`. The integration jobs never gate delivery; prod is `continue-on-error` (a failure is a warning). A missing secret skips that job's tests |
| Push to `develop` | Also in parallel: **Signed bundle** (`bundleRelease` against the `dev` environment's `ATTND_SERVER`, the `.aab` kept as the `android-bundle` artifact), then **Publish to Play internal testing** (Linux, `needs: [test, bundle]`, so failing unit tests block it) |
| Push to `main` | The same against `attnd.app` → **Publish to Play production** (staged rollout if `PLAY_ROLLOUT` < 1), plus the store listing text in the same edit |

**Gradle caches**: every job restores `~/.gradle/caches` (including the Gradle build cache, `org.gradle.caching=true`) and `~/.gradle/wrapper` with `actions/cache`, keyed per job (`gradle-test-…`, `gradle-bundle-…`, `gradle-integration-…`) on the build files' hash plus the commit, falling back to the newest earlier cache; pushes save a fresh one (pull requests only read). The project's `.gradle/` (configuration cache) is never cached, so signing settings never land in a cache.

Docs-only commits (`*.md`) don't run the pipeline; a newer push to `develop` cancels the older run (`main` never cancels). Gradle: parallel, build cache, configuration cache, 4 GB heap (`gradle.properties`). Publishing uses `scripts/ci/play_publish.py` (Play Developer API v3, service-account JWT) — no third-party actions or Gradle plugins. Release builds never contain the static API key. Server host: `ATTND_SERVER` → `BuildConfig.ATTND_SERVER` (default `attnd.app`). Setup checklist: `SETUP.md` in attnd-web.

## Building

```bash
./gradlew assembleDebug        # debug
./gradlew assembleRelease      # release (requires signing.properties); or ./build_release.command
```

## Running Tests

```bash
./gradlew test                 # unit tests only; or ./run_tests.command
ATTND_IT_SERVER=dev.attnd.app ATTND_IT_KEY=… \
  ./gradlew testDebugUnitTest -PintegrationTests   # live API tests only
```
Unit tests are pure JVM (domain + data/api) and never include `com.arcu.attnd.integration` (`app/build.gradle` excludes it unless `-PintegrationTests` is passed, which then runs only that package, never up to date or from the build cache).
The live tests are pure JVM too and go through the app's real client (`UrlConnectionHttpClient` → `AttndApi` → `ApiMapper`). Server and key come from the environment (`ATTND_IT_SERVER` host or `https://` origin, `ATTND_IT_KEY`; not from `local.properties`/`BuildConfig`); without either they are skipped. They only use the **DEMO** event (attnd-web `load_demo_event`: 18 locations, 51 schedule items, 4 sent notifications + 2 scheduled ones sent later, happening now; API-010 lower-case tags, 2–6 on everything; "Set time change" is the only urgent notification) and cover app-status, lookup/auth (401, re-attest retry), bundle ETag/304/404, join/leave, devices (never `"push_token": ""`), usage (bad entries → 200 with `accepted: 0`), the retired-version 410 and lossless decoding. Device ids are `ci-android-<GITHUB_RUN_ID>-<random>`; every joined device leaves in `@After`. If the DEMO facts change, update the iOS, Android and web suites together.

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
- **Screenshot pipeline**: builds the debug APK against the dev server (`ATTND_API_KEY` repo secret, `MAPS_API_KEY`) with `-PstoreScreenshots`: package `com.arcu.attnd`, signed with the upload key (`ANDROID_KEYSTORE_*`), because the Maps key only accepts the real app's package and certificates (otherwise the map is blank); the APK never leaves the emulator. It boots a headless `android-37.0;google_apis;x86_64` emulator (Pixel 9 Pro XL profile, KVM, emulator system libraries such as libpulse installed by the script, demo-mode status bar at 9:41) and captures `build/screenshots/raw/<screen>.png` (`screenshots.sh`); `compose_screenshots.py` makes 2160×3840 phone screenshots (purple gradient, caption, framed screenshot), the 1024×500 feature graphic and the 512×512 icon (from `drawable-nodpi/attnd_logo.png`) in `build/store/`.
- **`play_publish.py`**: `--listing DIR` (text), `--images DIR` (+ `--images-only`), or an `.aab` with `--listing` (release + text in one edit). A commit withdraws anything already in review and resubmits it together. Use `--not-for-review` if Play requires changes to be sent from the Console (after a rejection).
- Python for CI scripts runs in a venv (Pillow, `pyjwt[crypto]`); `__pycache__/` is git-ignored.

## Release

See `RELEASE_SETUP.md` for Play Store upload instructions.

## Related Repos

- **[attnd-web](https://github.com/apbeers/Attnd-Web)** — Django backend + REST API, and the **coordinating repo**: `API_V2.md` (contract), `API_CHANGES.md` (tracker)
- **[attnd-ios](https://github.com/apbeers/Attnd-iOS)** — SwiftUI iOS client
