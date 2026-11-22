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

- **Kotlin** with **Jetpack Compose**, **Material 3 Expressive** (`androidx.compose.material3` **1.5.0** from the Compose BOM 2026.11.01: `MaterialExpressiveTheme` + `MotionScheme.expressive()`, `TopAppBar`, `FloatingActionButton` + `ExpandedFullScreenSearchBar` + `SearchBarState`, `ShortNavigationBar` / `WideNavigationRail` via `NavigationSuiteScaffold`, `VerticalFloatingToolbar`, `ButtonGroup`, connected `ToggleButton`s, `LoadingIndicator` / `ContainedLoadingIndicator`, `LinearWavyProgressIndicator`, the `ListItem(content = …)` overloads) and **Material3 adaptive** (`NavigationSuiteScaffold` 1.5.0, adaptive 1.3.0, all from the BOM; `ListDetailPaneScaffold`, `currentWindowAdaptiveInfoV2`)
- **ML Kit GenAI Prompt API** `genai-prompt` `1.0.0-beta5` (**beta**) — on-device Gemini Nano for the event assistant's model path and the planner's preference extraction, runtime-checked
- **Live Updates** (Android 16 promoted ongoing notifications) through `androidx.core` 1.19.1 `NotificationCompat` (`ProgressStyle`, `setRequestPromotedOngoing`, `setShortCriticalText`), driven by inexact `AlarmManager.setWindow` alarms (see Planner & Live Update)
- **Networking**: `HttpURLConnection` + coroutines (`data/net/HttpClient.kt`)
- **JSON**: kotlinx.serialization (`ignoreUnknownKeys`, every field defaulted)
- **Images**: in-house loader (`ui/components/RemoteImage.kt`: memory LruCache → disk `data/ImageDiskCache.kt` in `filesDir/images/` → HttpURLConnection; BitmapFactory decode)
- **AndroidX lifecycle** ViewModel + `collectAsStateWithLifecycle`
- **Google Maps**: `maps-compose` + `maps-compose-utils` (googlemaps first-party; marker clustering, `MarkerComposable` pins), Play Services location (fused location + `FusedOrientationProviderClient` compass)
- **Google Routes API v2** `computeRoutes` (WALK) over HttpURLConnection for walking routes on the map (`data/WalkRouter.kt`; same `MAPS_API_KEY`, see Glance)
- **CameraX + ML Kit** — QR code scanning
- **Google Play Integrity** — device attestation for API authentication
- **Play Install Referrer** — deep link on first install via Play Store
- **Play services Block Store** (`play-services-auth-blockstore`) — keeps the device id across a reinstall on the same phone (cloud backup off; see Architecture)
- **Firebase Cloud Messaging** — push (optional at build time, see below)
- **compileSdk**: 37 minor 2 (platform `android-37.2`, `compileSdk { version = release(37) { minorApiLevel = 2 } }`) — **targetSdk**: 37 — **minSdk**: 36 (Android 16, owner Nov 22, 2026; no compatibility branches for older versions, no `Build.VERSION.SDK_INT` checks below 36, no `values-v36` splits, no core-library desugaring — `java.time` is native)
- **Toolchain** (Nov 22, 2026): Gradle 9.9.2, AGP 9.5.2, Kotlin 2.4.21, JDK 21 (any vendor: `gradle/gradle-daemon-jvm.properties` names no vendor; CI uses Temurin 21), build-tools 37.0.0. A fresh Linux container gets the SDK with `bash scripts/dev/setup_sdk.sh` (command-line tools, platform-tools, platform 37.2, build-tools into `~/android-sdk`, plus `local.properties` with `sdk.dir` and empty keys). Through a rate-limited proxy Maven Central may answer 429s: run Gradle with `--max-workers=1` the first time
- **Application ID**: `com.arcu.attnd` (debug builds: `com.arcu.attnd.debug`; the `dev` build type keeps `com.arcu.attnd`, which Play and `google-services.json` need)
- **Build types**: `debug` (local, `.debug` suffix, `BuildConfig.DEV = true`); `dev` (`initWith(release)`: upload-key signed, Play Integrity, no static key, **not debuggable** (Play rejects debuggable bundles on every track), **no R8** (`minifyEnabled false`, readable stacks), `BuildConfig.DEV = true`) for `develop` → Play internal testing; `release` (R8 minify + resource shrinking, `DEV = false`) for `main` → production. `BuildConfig.UPLOAD_LOGS` is false only for `debug`: local debug builds never upload logs (as iOS's Xcode builds), `dev` builds do. `proguard-rules.pro` keeps the serializers of every `@Serializable` class and `SourceFile,LineNumberTable` (renamed to `SourceFile`) for retrace; Maps, ML Kit, Play services and Firebase ship consumer rules

Exact plugin and dependency versions live in the version catalog `gradle/libs.versions.toml`.

## Project Structure

```
app/
  build.gradle                    # Reads API keys from local.properties; applies google-services only if app/google-services.json exists
  src/main/java/com/arcu/attnd/
    AttndApplication.kt           # App init (attestation, notification channel) + AppGraph (manual DI: repository, app scope)
    OpenIntents.kt                # Explicit intents to MainActivity with attnd-open:// data (Live Update, reminders)
    MainActivity.kt               # Single activity: intents (App Links, notification taps, debug screenshot extras), foreground refresh loop
    domain/                       # Pure Kotlin, unit tested — no Android imports
      Models.kt                   #   Event (+ API-016 `websiteUrl`, API-018 `context`, API-023 `theme`), EventLocation (+ API-022/031 `imageCredit`, `imageAttributionUrl`), ScheduleItem, EventNotification, EventBundle (+ API-027 `liveActivity`), GeoPoint
      EventContext.kt             #   API-018 context models (API-030 `Performer.appleMusicUrl`, `AppleMusicLinks`), API-023 `EventTheme`, API-027 `LiveActivitySettings`
      DeviceIdentity.kt           #   Device id resolve: the app's copy, else Block Store's, else a new UUID
      WebLinks.kt                 #   `httpsUrl`: absolute https URLs only (API-016 `website_url`), `host`
      Theme.kt                    #   API-023 theme → Material slots (ThemePalette: fallbacks, WCAG on-colours, soft wash, highlight), LiveTheme accent
      DisplayConfig.kt            #   Server-driven `display` object + client defaults
      EventTime.kt                #   ISO parsing and formatting in the EVENT's time zone
      ScheduleGrouping.kt         #   timeline / by_day / by_location / now_next, search, ended/now
      LocationGrouping.kt         #   category grouping, filters, mappable pins, bounds
      JoinLinks.kt                #   /join/<code>/ App Links + install-referrer parsing; API-026 `previewToken` (`?preview=`)
      PreviewPhone.kt             #   API-026 `PreviewPoll`: changes/ answer → refresh / wait / stop, when it runs, the capsule, the join answer
      ScreenshotRequest.kt        #   Debug store-screenshot mode: launch extras → screen to show
      UsageAggregator.kt          #   API-005 counts by (name, detail, UTC 5-minute window) + metric names
      SupportInfo.kt              #   API-004 build/device/event details, support code, copy text
      ImageCachePlan.kt           #   API-007 image cache: file names, referenced URLs, stale files, budget; API-031 live place photos (never stored)
      LiveUpdates.kt              #   API-007 poll interval (15 s after a failure), push refresh rules + jitter (API-026 `jitter_ms`), network tidy-ups (app-status hourly, devices on change, 15 s refresh skip), offline notice text
      SearchText.kt               #   Search normalisation (lower-case, accents folded, punctuation → spaces) with index mapping
      SearchQuery.kt              #   Query parsing: tokens + time words (now/next/tonight/days/clock times), keywords(); TimeMatcher
      EventSearch.kt              #   Local search over the bundle: scoring, ranking, result detail lines, suggestions
      AssistantPrompt.kt          #   On-device assistant: context retrieval, prompt, [S12]-style citations
      OpenLinks.kt                #   attnd-open://<event>/<target> internal links (notification and Live Update taps)
      RelativeTime.kt             #   "in 12 min" / "Started 5 min ago" / "tomorrow 7:30 PM" / "in 2 weeks" (event tz)
      Geo.kt                      #   Distance text (region units), haversine, bearing, compass points, arrow rules
      Walking.kt                  #   Walk estimate, route plausibility, walk texts; EncodedPolyline decoder
      Glance.kt                   #   Now/next per location, pin subtitles, Now playing, progress; UnreadUpdates
      Hearts.kt                   #   Hearted ids per event (encode/decode), API-009 metric names; since 3a My schedule's mirror
      MySchedule.kt               #   iOS My schedule port: entries (items, timed / saved places, reminders, fingerprints), changes + API-009 metric, rows, upNext, clashes, "When?" times, migration, apply(plan) / reconcile, meals covered, organizer changes, JSON codec
      TabLayout.kt                #   iOS tab rules (pure, not wired until phase 4): EventTab (+ Home), resolve (Home first + default, 5-tab cut), titles, optionsTab, MapNavigation
      PlanSimilarity.kt           #   TF-IDF cosine similarity between schedule items (planner)
      Planner.kt                  #   Plan model + the deterministic planner (scores, DP choice, pace, meals, reasons, swap, changes)
      PlanCodec.kt                #   Plan ⇄ JSON for the device's preferences (lenient)
      PlanRequest.kt              #   Model prompt + JSON validation, plain-rule reading, merge, plan-request detection, example chips
      LiveFollow.kt               #   Live Update content, windows, wake-ups, reminders, urgent block
      AssistantActions.kt         #   Event assistant: action registry (names, params, mutates, confirm), EntityRef ("s12"), checks, dispatcher + executor interface
      AssistantBlocks.kt          #   Response blocks, block JSON, BlockValidator (ids, caps)
      AssistantRouter.kt          #   Rule-based router: command grammar, entity resolution, layout rules, read-action rendering
      AssistantModel.kt           #   Model turn: prompt, retrieval (≤ 12), parser + rule-based fallback, conversation
      PlanEditing.kt              #   Plan ops, PlanEditor (constraints + re-solve), PlanDiff, undo stack, versions, ops JSON
      DayRoute.kt                 #   The plan's walking stops, legs and totals
      LiveAssist.kt               #   Running late, check-off, skipped, gap filler, like clash, recap, organizer changes
      WhyNot.kt                   #   "Why isn't X in my plan?"
      PlanShare.kt                #   API-015 share payload/links, group plans
      PlanTab.kt                  #   Plan tab spec: proposals (in-place previews: rows, diff pill), "do it"/"the second one" replies, no-AI rules
      AssistantFlow.kt            #   Assistant screen rules: turn path (router / model / plan), block data (timeline sources, compare, summaries), live hints, shared plans (use / merge), transcript codec
      ScreenLayout.kt             #   API-024/027 server-driven screens: LayoutScreen/Section/Override, ScreenLayouts.decode (lenient), SectionCatalogue (VERSION 2), defaults, validate, overrides, `when` conditions, resolve, ScreenFacts (incl. the API-018 context parts), DetailColumns, HomeColumns(+Widths), TabletopSplit, AboutText, TabListLayout, UpdatesLayout, OnboardingLayout
      Component.kt                #   One component catalogue (chat blocks + layout sections; aliases plan_today/liked → my_schedule; chat decode/encode/valid)
      FoldRules.kt                #   What each screen keeps across fold ↔ unfold (primary pane, folded state)
      QrCode.kt                   #   In-house QR encoder (byte mode, versions 1–40, Reed–Solomon, masks) for the share block
      AppLog.kt                   #   API-013 logging facade (level first, Redact, sink), error kinds, Trace sections + perf.signpost
      Logging.kt                  #   LogLevel / LogCategory / LogRecord (v1 JSON line), LogJson, the viewer's LogFilter
      LogEvents.kt                #   Every event name we emit (all in attnd-web LOG_EVENTS.md)
      LogRedaction.kt             #   Redact: path templates, text → lengths, secrets/emails/ids masked, 0.01° coordinates
      DebugMode.kt                #   Debug codes D-XXXXX, 10-min sessions (no pre-roll), levels (release: errors only)
      Diagnostics.kt              #   Log upload batches / body / Retry-After; exit reports (crash/anr/low_memory/exit)
      PerfMetrics.kt              #   API-014 perf_* names and bucket labels
    data/
      net/HttpClient.kt           #   HttpURLConnection client (no caching, so 304s reach us)
      api/AttndApi.kt             #   v2 endpoints; 401 → re-attest → retry once; ETag/If-None-Match
      api/ApiDtos.kt              #   @Serializable wire format (all fields nullable) + ApiJson config
      api/ApiMapper.kt            #   DTO → domain, applying defaults
      api/ContextMapper.kt        #   Lenient JsonElement decoding: API-018 context, API-023 theme, API-027 live_activity
      EventRepository.kt          #   Single source of truth (StateFlow bundle), join/leave/refresh, disk cache, push token state
      PreviewPoller.kt            #   API-026 preview phone: `changes/` long-poll while resumed (`EventViewModel.previewSync`)
      ImageDiskCache.kt           #   API-007 offline images (filesDir/images/): fetch, prefetch, clean-up
      OnDeviceAssistant.kt        #   ML Kit GenAI Prompt API wrapper: availability, model download, answer (guarded)
      UsageReporter.kt            #   API-005 anonymous usage counts: aggregate, flush every ~60 s / background / leave
      AttestedTokenProvider.kt    #   Bearer token: attestation JWT or static key
      InstallReferrer.kt          #   Play Install Referrer → event_code
      BlockStoreDeviceId.kt       #   The device id's Block Store copy (this phone only)
      attest/AttestationApi.kt    #   Unauthenticated /api/v1/attest/ endpoints
      prefs/DevicePrefs.kt        #   device id, joined event id/code, ETag, push token, preferences, per-event hearts / updates seen / banner dismissed
      ScheduleStore.kt            #   My schedule per event (DevicePrefs `my_schedule_<id>` JSON, hearts mirrored), one-time migration from hearts + plan
      HeartStore.kt               #   Read-only hearts view of My schedule (+ version flow)
      ScheduleController.kt       #   My schedule (AppGraph): add / remove / time / reminder / undo, API-009 counts on membership changes, plan reconcile, PlanStore.onApply → applyPlan, fingerprints, like clash
      PermissionsState.kt         #   What the system allows (notifications, location), read on demand (AppGraph)
      DeviceSensors.kt            #   UserLocationSource (fused location) + HeadingSource (compass), ref-counted, only while visible
      WalkRouter.kt               #   Routes API computeRoutes + per-location cache; AppSignature (signing cert SHA-1)
      PlanStore.kt                #   Per event: plan, Follow on Lock Screen, skipped items, urgent block, reminders shown (version flow)
      log/                        #   API-013/014: LogStore (ring buffer), AndroidLogSink (logcat + writer thread + fg/bg + network), LogUploader,
                                  #   DiagnosticsController (debug mode, levels, export), ExitReporter (ApplicationExitInfo + crash handler),
                                  #   PerfRecorder (perf_* into usage), LaunchTiming, PermissionLog
    live/                         # Live Update (spec §8): LiveUpdateController (AppGraph), LiveNotifications (channels, Live Update, reminders, urgent), LiveUpdateReceiver (alarms, actions, boot)
    push/                         # FCM service + notification channel / display
    ui/
      EventViewModel.kt           #   AppUiState (one combine of the controllers' flows) + the screens' actions, delegating to:
      NavigationController.kt     #   Selected tab (TabLayout), focus requests, the search sheet, My plan, Options, open-link routing
      PlannerController.kt        #   Make / re-plan / swap / keep / clear plans (+ planner timing)
      OnDeviceModelController.kt  #   Gemini Nano availability and download
      AttndApp.kt                 #   Root composable, theme (accent colour), EventShell with NavigationSuiteScaffold
      MapTab.kt                   #   Foldable-aware map + secondary pane
      assistant/                  #   Event assistant: AssistantController (state, proposals, ActionExecutor, model path, live hints), PlanTab (MyPlanTab, input bar, answer card, pills), AssistantBlocksUi (one component per block type), MiniMap (+ QrImage, day routes), AssistantIntents (share, calendar, Maps)
      components/                 #   RemoteImage, FoldAwareSplit, IdListDetail, banners, chips, QR scanner; Glance.kt (DistanceArrow, WalkTime, HeartButton, AskAboutButton, PeekSheet, UpdateBanner, LocalEventActions, LocalScreenshotMode)
      screens/                    #   Launch, Map, Locations, Schedule, Notifications, Flyer, Options (+ LogViewerScreen), Search (SearchFab, EventTopBar, SearchDetail), Onboarding (tour)
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
- **Offline images (API-007)**: `ImageDiskCache` (in `AppGraph`, on `appScope`) keeps the bundle's images in `filesDir/images/<sha256(url)>` (not the cache dir, so the OS doesn't purge them mid-event; excluded from backup by `res/xml/data_extraction_rules.xml`). `ImageLoader` reads memory → disk → network; downloads go to a temp file and are renamed into place. After every applied bundle, after `restore()` and on a `304` (no-op once complete) the repository prefetches `ImageCachePlan.referencedUrls` (header/event logo, flyer unless it is a PDF, location images) 4 at a time; once every image has been attempted, files the bundle no longer references are deleted (new images first, a failed download keeps the old file). Budget ~300 MB per event, files > 15 MB are never stored (shown from memory). API-031 live Google place photos (`/img/places/<id>/`, `ImageCachePlan.isLivePlacePhoto`) are never prefetched or written to disk (Google's terms): `ImageLoader` keeps them in memory only. Leaving/switching events deletes them with the bundle. URLs are treated as immutable (an existing file is never re-downloaded). A PDF flyer is not cached (no FileProvider; it opens the URL in the system viewer).
- **`EventViewModel`** combines repository state with UI-local state into one `AppUiState` (phase LOADING / LAUNCH / EVENT, selected tab, join/nearby state, one-shot focus requests and messages). Screens receive state slices + callbacks only; they hold no app state (only UI state like search text). Since phase 2 of the parity plan it is split like iOS's `AppState` + controllers: UI-scoped `NavigationController` (pure Kotlin, `NavigationControllerTest`), `PlannerController`, `OnDeviceModelController` and the existing `AssistantController`, each with its own `StateFlow` that `uiState` combines; app-scoped `ScheduleController` and `PermissionsState` in `AppGraph`. The VM keeps joining, leaving, screenshot mode, sync, onboarding, settings and usage, and its public functions delegate, so the screens didn't change.
- **`UsageReporter`** (in `AppGraph`, runs on `appScope`) collects anonymous usage counts; `EventViewModel` records them with one-line `track(...)` calls, and `EventRepository` flushes it before leaving or switching events.
- Device ID: a random UUID (never a system identifier), used for join/leave/devices and as `X-Device-Id`. Never sent with usage counts. It survives a reinstall on the same phone (iOS `DeviceIdentity`, the Keychain there): `DeviceIdentity.resolve` takes the app's own copy (`DevicePrefs.storedDeviceId`), else Block Store's (`BlockStoreDeviceId`, `setShouldBackupToCloud(false)`, key `attnd_device_id`), else a new UUID, and whichever store lacks it gets it. `AttndApplication.onCreate` resolves it before attestation or any API call: only on an install's first launch does it wait for Block Store (≤ 2 s); later launches fill Block Store in the background when it lacks the id. The id lives in its own prefs file `attnd_device.xml`, excluded from Auto Backup and device transfer (`data_extraction_rules.xml`), so two phones never share one (older builds' `attnd_prefs.device_id` is moved there once, `storage.prefs.migrated`). Not verifiable without a device + reinstall.
- **Decoded for later phases** (not shown yet; lenient, raw `JsonElement` in the DTOs so a wrong type never fails the bundle, `ContextMapper`, iOS rules 1:1): API-018 `context` on the event, locations and schedule items (API-030 `performers[].apple_music_url`, https `music.apple.com` or a subdomain only); API-023 `event.theme`; API-022 / API-031 `locations[].image_credit` / `image_attribution_url`; API-027 `bundle.live_activity`; `bundle.layout` kept raw in `BundleDto` only (the app doesn't send `X-Attnd-Sections` yet, so `home` in `display.tabs` is ignored).

### Usage reporting (API-005)

`POST events/<id>/usage/` with `{platform: "android", events: [{name, detail?, count, at}]}`, `at` = UTC start of the 5-minute window (e.g. `2026-11-14T16:05:00Z`). No device id or other identifier, never search text. `UsageReporter` aggregates per event in a `UsageAggregator`, sends at most every 60 s while there is data, when `MainActivity.onStop` runs (not on rotation) and before leave/switch; a failed batch is retried once on the next flush, then dropped; errors are swallowed.

| Metric | Where |
|--------|-------|
| `app_open` | `EventViewModel.foregroundSync` on the first resume after launch or after the app was backgrounded, while in an event |
| `tab_view` (`home`, `map`…`notifications`, `options`) | `EventViewModel` init: counted whenever the visible screen (or Options) changes, including the initial one; each tab sends its key (`TabLayout.usageName`, Home `home`), My plan sends nothing |
| `location_view` / `schedule_item_view` | `LaunchedEffect` in the Locations/Schedule detail panes, the search screen's details and the hidden-tab overlays → `onLocationShown` / `onScheduleItemShown` |
| `notification_open` | `MainActivity` notification-tap intent (`notification_id` extra) → `openNotifications(id)` |
| `search` | `SearchField` and the assistant's input bar (`rememberSearchReporter`, source `assistant`): on send or once the non-empty text settles for 1.5 s → `onSearch` |
| `refresh` | `EventViewModel.refresh` (pull to refresh only) |
| `location_heart` / `location_unheart`, `schedule_item_heart` / `schedule_item_unheart` (API-009, detail = id) | `ScheduleController` (`MyScheduleChange.metric`): the heart (`EventViewModel.toggleHeart` → `setLiked`), the assistant's like / unlike, add / remove / undo and a plan filling My schedule (`applyPlan`), only when membership changes (never a time / reminder change, the migration, a restore, rejoin or leave) |

### Support info (API-004)

Options → **Status** (what the pills say, or "Up to date · Updated 2:14 PM") and **Permissions** (phase 4, iOS `PermissionsSection`; `ui/screens/OptionsPermissions.kt`, rules `domain/PermissionRules.kt`): Location and Notifications with the system's status (`PermissionStatus.of(granted, askedBefore, rationale)`; "asked before" is remembered in `DevicePrefs.permissionAsked`, since Android has no "not determined"). Allowed → Attnd's own switch (the dot and walk times / the opt-in sent as `subscribed`); never asked (or Android may still ask) → the switch asks; denied → **Open settings** (`ACTION_APPLICATION_DETAILS_SETTINGS` / `ACTION_APP_NOTIFICATION_SETTINGS`). Re-read on every resume (`LifecycleResumeEffect`). On every new join, what Android already allows is turned on (`EventViewModel.applySystemPermissionsOnJoin`, never a prompt). Follow on Lock Screen is on by default (iOS batch 7, `PlanStore.follows`).

Options shows an "Internal test build" card when `BuildConfig.ATTND_SERVER != "attnd.app"`, and an **About & support** section from `AppUiState.supportInfo` (built in `EventViewModel`): version/code, build type, server, API version, Android version, device, event name/code/id/content version, last refresh (device time zone), the **support code** (first 8 characters of the device id, no dashes, upper case) and **Copy support info** (plain text including the full device id, via `LocalClipboard`).

## Search (local, app-wide)

Shared spec with iOS (same rules and the same unit-test cases, `unit/EventSearchTest.kt`, fixed `now`, America/Chicago). No server call; it searches the bundle on disk, so it works offline.
- **Entry point** (phase 4, iOS `EventSearchSheet`): a Material 3 floating button on every tab (`SearchFab`) opens the **search sheet** (`ui/screens/SearchSheet.kt`, a full-height `ModalBottomSheet`, no AI: its own back stack, Close, the field focused with the keyboard up; opened straight onto an item the keyboard stays hidden). With Gemini Nano AVAILABLE / DOWNLOADABLE / DOWNLOADING (plan §4.3, `AppUiState.planButton`, never in screenshot mode) the same button is the **My plan** button (`AutoAwesome`) instead and opens My plan (below). A question that matches nothing as typed shows the results for its keywords (`SearchQuery.keywords`). Where the button sits: in the wide navigation rail's header when the rail is shown (`NavigationSuiteScaffold(primaryActionContent = …)`); on the Map tab otherwise as the FAB of the map's `VerticalFloatingToolbar` (`floatingActionButton`, so the two never overlap); elsewhere in the inner `Scaffold`'s `floatingActionButton` slot (bottom-end, above the navigation bar). Back pops a detail, then closes the sheet. The organizer tabs' top bar is a plain `TopAppBar` (`EventTopBar`: event logo, event name, and the Options gear only on `TabLayout.optionsTab`'s root); Home has no top bar.
- **Matching** (`domain/EventSearch.kt`, `SearchQuery.kt`, `SearchText.kt`): both sides normalised; every token must match (AND across fields); a word equal to the token (4), starting with it (3), containing it (2) or, for 5+ character tokens, Levenshtein ≤ 1 (1; a transposition is 2 edits). Weights: names 3; tags (schedule items and locations, API-010), location name, category, snippet, address, event code 2; descriptions and notification bodies 1. Schedule items without a location are searchable as "General". Boosts: now +8, next +4, ended −1000 (always last). Ties: kind (schedule, location, notification, event), title, id.
- **Time words** in the event's time zone: `now`/`happening now`, `next`/`up next`/`soon` (2 h), `today`, `tonight` (starts ≥ 17:00 today), `tomorrow`, weekdays (`sat` only when no content word is "sat"), clock times `3pm`, `3 pm`, `15:00`, `3:30` (both 03:30 and 15:30), `noon` (in progress then, or starting ≤ 30 min after, on the event's days). A bare `7` is not a time. Time words filter only schedule items; a time-only query returns only schedule items. The search field never drops stop words; `SearchQuery.keywords()` (stop words removed) is only for questions (assistant).
- **Results**: kind icon, title with the match in bold primary, one detail line (`EventSearch.detail`: "Sat 7:30–8:30 PM · Main Stage", "Food · Street tacos", "Sat 3:55 PM · body…", "Nov 14–15 · address"; absolute times, never relative), Now/Next/Ended label, distance + arrow for locations, a heart for locations and schedule items.
- **Liked** (hearts; the UI says "like" since the assistant spec §0, identifiers / prefs / API-009 names keep "heart"): once anything is liked, the empty-state suggestions include a **Liked** chip that opens the hearted list (`EventSearch.hearted`: hearted schedule items in time order, then hearted locations in the organizer's order); it is not a query word.
- **Navigation**: a tapped result opens its detail on the sheet's own back stack (`SearchEntry`, `SearchDetail`; Back returns), so items of tabs the layout hides still open; location ↔ schedule links push further entries (`search.open` logged). External requests (notifications, Live Update links; `NavigationController.route`) open the item on its own tab (Places / Schedule / Map / Updates) when the layout shows it (notifications scroll to and briefly highlight the item), otherwise in the search sheet (`SearchRequest.detail`); searches (`attnd-open://…/search?q=`) open the sheet with the query.
- **Usage**: the existing `search` metric only, counted exactly like the per-tab fields (`rememberSearchReporter`: IME search or a non-empty query settled for 1.5 s; never the text). No `tab_view` for search; detail views count `location_view` / `schedule_item_view` as elsewhere.

## My schedule (iOS parity, phase 3a: domain + storage; the visible UI is phase 3b)

A 1:1 port of iOS `MySchedule.swift` (`domain/MySchedule.kt`, `unit/MyScheduleTest.kt` = iOS `MyScheduleTests` on `PlannerFixture` / `AssistantFixture`, plus store / controller and meals-covered cases). One list per event replaces likes and the stored plan: schedule items, places at a chosen time (default 45 min) and places saved without a time; per entry a reminder (null = 15 min, 0 = off, else 5 / 10 / 15 / 30 / 60) and, for items, `Planner.fingerprint` (organizer changes: moved / changed / cancelled, Keep).
- **Storage** (`ScheduleStore`): `DevicePrefs` `my_schedule_<id>` (lenient JSON: unknown keys ignored, a bad entry dropped, bad text → empty), kept after leaving. Every write mirrors the entries into the old `hearts_<id>` text, so `HeartStore` (now a read-only view), search's Liked list, the filters, the planner's must-includes, the assistant and the Live Update keep reading hearts; an older build still sees the hearts. No debounced writer (`SharedPreferences.apply` is already asynchronous).
- **Migration** once per event (the first read without `my_schedule_<id>`): hearted items + the stored plan's items → item entries, hearted places → saved places, the plan's meal breaks with a food place → place entries at the break. No metrics. Fingerprints come from the next bundle (`ScheduleController.onBundle`, AppGraph collects the bundle).
- **Changes** (`ScheduleController`, AppGraph): `setLiked` (the heart: an item, or a place saved without a time), `add` (a place with a time), `remove`, `move`, `setReminder`, `undoAdd`, `keepOrganizerChange`; API-009 metrics only on membership changes; after a change made elsewhere the stored plan is reconciled (`MySchedule.reconcile`: removed items leave it, items added on its days join it as "In your schedule"). `PlanStore.onApply` (every new plan version and undo) → `applyPlan` (`MySchedule.apply`: the plan's items and meal places added, what an edit dropped on its days removed; clearing the plan keeps My schedule). Timed food places cover that day's lunch / dinner break (`mealsCovered` → `PlanConstraints.mealsDone` in `PlannerController`). The like-clash chip is computed before the add (the add reconciles the plan).
- **Pure helpers for 3b**: `rows`, `sectionRows`, `upNext`, `rows(day)`, `clashes`, `span`, `suggestedTimes` ("When?"), `MySchedule.date` (clock times), `placeIds` (the My Schedule chip), `reminders` (fire times per entry; texts / posting stay with the Live Update).
- **Not yet** (later phases): the visible UI (heart menu, When?, Remind me, Undo snackbar, Schedule sections, My Schedule chip, per-entry reminders posted) is 3b; WhyNot / router / recap wording and the `schedule_*` actions are 6a; the tab shell (`TabLayout`) is 4.

## Glance, distances, walking routes, hearts (shared spec with iOS, wave 1)

Everything is on the device; pure rules in `domain/` with JVM tests (`RelativeTimeTest`, `GeoTest`, `WalkingTest`, `GlanceTest`, `HeartsTest`; fixed now Sat Nov 14 2026 2:00 PM America/Chicago, en_US; same decisions as iOS).
- **Relative time** (`RelativeTime`): no end / an end not after the start = start + 1 h; ending exactly now is "Ended"; "Started just now" (< 1 min), "Started 20 min ago", "Now" (long form adds " · ends 8:30 PM", "· ends Sun 1:00 AM" on another day); "Starting now", "in 12 min", "in 3 h 20 min" (< 6 h), "today 7:30 PM", "tomorrow 7:30 PM", "in 3 days", "in 1 week" (days/7, < 5 weeks), calendar months/years (≥ 1). Minutes round down. Used by schedule rows (updates every 30 s with `rememberNow`), schedule detail (long form), location schedules, peek and the launch screen's nearby events (`forNearby`: the event's start, else `starts_in_hours` turned into a date; without an end it lasts until the end of its first day). Search subtitles keep absolute times.
- **Distance** (`DistanceFormat`, `DistanceUnits.forRegion`): US/GB/LR/MM → feet under 0.1 mi (nearest 10, min 10), miles with one decimal under 10, whole miles; elsewhere metres under 1 km (nearest 10), km with one decimal under 10. Locale decimal separator. Spoken: feet/miles/meters/kilometers. Haversine, R = 6,371,000 m. "Here" under 20 m.
- **Arrow** (`DistanceArrow` in `ui/components/Glance.kt`, `Geo.direction`): bearing − heading (true north), animated the short way; hidden without a heading, with negative heading accuracy, with approximate location only, or a fix worse than 100 m (`Geo.arrowAllowed`); content description "Northeast, 0.6 miles" (8 points centred on 0°, 45°, …). Shown on nearby events, location rows/cards/detail, the map card, location search results, schedule detail. `HeadingSource` uses `FusedOrientationProviderClient` (play-services-location 21.4.0, 200 ms) and falls back to the rotation-vector sensor (+ declination); both are display-rotation corrected and run only while a composable showing an arrow is visible and resumed (`rememberHeading`, `LifecycleResumeEffect`, reference counted). `UserLocationSource` (fused, high accuracy, 5 s) likewise (`rememberUserLocation`); nothing without the location permission.
- **Walking** (`Walking`): estimate = straight × 1.2 ÷ 1.3 m/s, minutes rounded up (min 1, "1 h 6 min"); only within 8,000 m. Rows (schedule rows, detail, peek): `WalkTime` → "8 min walk" or "You're here" under 20 m. Map: tapping a pin selects it (no info window) and shows `SelectedLocationCard` (now/next, distance + arrow, heart, Details, Directions → Google Maps; the app never navigates) and, within 8 km with permission, a route: `WalkRouter` POSTs `https://routes.googleapis.com/directions/v2:computeRoutes` (`travelMode: WALK`) with `X-Goog-Api-Key` = `MAPS_API_KEY`, `X-Goog-FieldMask: routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline`, `X-Android-Package` and `X-Android-Cert` (SHA-1 of the current signing certificate, upper-case hex without colons, `AppSignature` via `PackageManager` signing info); `EncodedPolyline.decode` is our own. A route longer than 2.5 × the straight line, no route, offline or any error (e.g. a debug build the key's app restriction rejects) → the estimate as a dashed straight line, "about 8 min walk"; a route reads "12 min walk · 0.6 mi". One result is cached per location and reused while the user is within 50 m of where it was computed; failures aren't cached. The Routes API must be enabled for the key (attnd-web `SETUP.md`).
- **Glanceables** (`Glance`): pins (`MarkerComposable` / `clusterItemContent`) get a live dot when something is in progress there and "Next 7:30" (no AM/PM) when the next item starts within 3 h; a **Now playing** strip under the map chips ("Main Stage · Prairie Lights", items in progress at locations with coordinates, by start, location order, id; tap → select); location rows/cards/detail show "Now: X · Next 8:30 Y" ("Next Sun 10:00 Y" on another day; now = the earliest-starting item in progress); schedule rows show the relative time, a thin progress bar while in progress and the walk time. **Peek**: long-press a location or schedule row (`ListItem(onLongClick)`, cards `combinedClickable`) → `PeekSheet` (`ModalBottomSheet`: key info, Heart, Show on map, Open). **Updates**: the Updates navigation item has an unread badge (`UnreadUpdates`: updates with a send time after the tab was last seen, every dated one before it was ever seen; seen = max(now, newest send time) while the tab is visible; per event in `DevicePrefs`), and the newest unread update sent within 24 h is a dismissible `UpdateBanner` above the other tabs (dismissed id per event).
- **Hearts** (`Hearts`, `HeartStore`, `HeartButton`): on schedule items and locations in rows, cards, details, the map card, search results and the peek; stored per event in `DevicePrefs` (SharedPreferences, included in Auto Backup) and kept after leaving. A **Liked** filter chip (only once something of that kind is liked) on Schedule, Locations and the Map (hearted pins only); the assistant's Liked chip. `EventActions` (`LocalEventActions`, provided in `AttndApp` around `EventShell`) carries hearts, the toggle and "Show on map" (`EventViewModel.showOnMap` → `mapFocusLocationId`) so rows don't thread them through every list.
- **Tags** (API-010) arrive lower case (`EventLocation.tags`, `ScheduleItem.tags`): filter and search them as sent; `Tags.display` capitalises for display only ("Live music · Free"). **Urgent** notifications (API-011, `EventNotification.priority`, unknown → normal) are highlighted in the Updates list (error container, "Urgent", warning icon), the update banner and the search detail; for the urgent push channel and the Live Update see Push Notifications and Planner & Live Update.
- **Screenshot mode** (`LocalScreenshotMode`): no location tracking (no distances, arrows, walk times, routes), no map glance features (live dots, "Next" labels, Now playing), no unread badge or update banner; no onboarding, planner, Live Update or reminders (`LiveUpdateController.suspended`).

## Planner, onboarding, Live Update (shared spec with iOS, wave 2)

Everything on the device; pure rules in `domain/` with JVM tests on one fixture (`unit/PlannerFixture.kt`: a DEMO-like festival, America/Chicago, Sat Nov 14 – Sun Nov 15 2026, gates 10:00 AM, now Sat 10:00 AM, DEMO coordinates, hearted 101 The Prairie Lights + 114 Neon Armadillo; `PlannerTest`, `PlanRequestTest`, `LiveFollowTest`). iOS implements the same rules and cases.

**Planner** (§6) — on every phone except in screenshot mode (`PlanUiState.available`, plan tab spec): with Gemini Nano AVAILABLE the model reads typed requests; without it plans come from likes and `PlanRequest.heuristic` alone (`buildPlan` skips the model).
- **Preferences** (`PlanPreferences`): liked/disliked tags and categories, `similarTo` ids, days, earliest/latest, duration (minutes), pace relaxed/balanced/packed, meals, kid-friendly. The model only extracts them: `PlanRequest.prompt` asks for one JSON object (`liked_tags, disliked_tags, liked_categories, disliked_categories, similar_to, days, earliest, latest, hours, pace, include_meals, kid_friendly`) listing NOW, DAYS, TAGS (≤ 40 by use), CATEGORIES, HEARTED and ITEMS (≤ 60 upcoming, "id name (tags)"); `OnDeviceAssistant.extractPreferences` (temperature 0, ≤ 320 tokens; plain `generateContent`: the typed/structured-output API needs the alpha `genai-schema` artifact). `PlanRequest.parse` takes the first `{`…last `}` and validates everything (tags lower case and in the bundle, categories case-insensitively → the event's spelling, ids in the schedule (also "S103"), days that are event days, `HH:MM`, 0 < hours ≤ 24, pace, booleans also as "true"). `PlanRequest.heuristic` reads the request with plain rules (tag/category phrases, plural `s`, a negation word among the two before → disliked; item names of 4+ chars → `similarTo`; weekdays/today/tonight/tomorrow; "after/from 3pm" / "before/until/till/by 9" (1–7 without am/pm = pm); else morning ≤ 12:00, afternoon 12–17, evening/tonight/night ≥ 17:00; "3 hours", "a couple of hours"; relaxed/packed words; meal words; kid words). `PlanRequest.merge(model, rules)`: sets unioned (disliked wins), days/times/duration from the model when given, pace the model's unless balanced, booleans OR; the rules alone when the model fails.
- **Similarity** (`TfIdfSimilarity`): document = name + tags + location category + description + location name; terms = `SearchText.words`, ≥ 2 chars, not `SearchQuery.STOP_WORDS`; idf = ln((1+N)/(1+df)) + 1 over the whole schedule; weight = count × idf; cosine (sums in sorted term order). ML Kit's on-device `genai-embedding` exists only as 1.0.0-alpha1 (Oct 2026): not used, so both platforms compute the same plans.
- **Scheduling** (`Planner`, rules 1–12 in its KDoc, the source of truth; rules 13–20 add the assistant's plan-edit constraints, see Event assistant): end = end or start + 1 h; booked minutes rounded down; `from` truncated to the minute; days = wanted event days ≥ today, else the first event day ≥ today with a candidate; window = max(from, day at earliest) … day at latest (next day when latest < 06:00 or ≤ earliest; else next midnight), capped by the duration; candidates start that day inside the window (start ≥ window start, end ≤ window end), kid-friendly drops `ADULT_TAGS` (21+, 18+, adults, adults only, adult), score ≤ 0 dropped (hearted never dropped); **score** in integer milli-points floor(x·1000 + 0.5), x = 0.5 + 3·hearted + 2·S (max similarity to hearted ∪ similarTo, not itself) + M (liked tag, liked category or hearted location) + K (kid-friendly and a `FAMILY_TAGS` tag: kid-friendly, kids, family, all ages, family-friendly) − 2·D (disliked tag/category); **gap** b after a: a.end + walk + 5 min ≤ b.start (walk = 0 same location or no coordinates, else `Walking` estimate in whole minutes rounded up, ≥ 1); **hearted must-include** by (score desc, start, end, id), a clash makes it an "or instead" of the earliest-starting kept item (then lower id); **pace** cap = max(floor(span × 50/70/90 %), kept hearted minutes), span = max(window start, first candidate start) … min(window end, last candidate end); **choice** = DP over (last item, booked minutes): max total score, then fewer booked minutes, fewer items, lexicographically smallest candidate positions; **meals** lunch 12:00–13:30 / dinner 18:00–19:30 clipped to the window, earliest of {clipped start, planned ends inside} rounded up to 5 min whose 45 min overlap nothing, suggesting the food location (category/tag contains "food") nearest the previous planned item (sort order, id), else the first; **reasons** in order (iOS wording since phase 3a) "Picked by <n> of you" (group) / "In your schedule" / "You added this" (pinned) / "Because X is in your schedule" (S ≥ 0.2) / "Because you asked for <tag>" / "Because you asked for <category>" / "At X, in your schedule" / "Kid-friendly" / "Similar to X" / "Fits your day"; **swap** ≤ 3 alternatives: "or instead" first, then candidates fitting between the neighbours by (score desc, start, id) (ignores the pace cap); **changed** = a planned item gone or its name/start/end/location differs (`Planner.fingerprint`). `Planner.summary`: "8 items, 10:00 AM – 11:00 PM, with a lunch break".
- **UI**: the **My plan** tab (see Plan tab). `PlanRequest.looksLikePlan` (plan/itinerary/highlights/"my Saturday"/"3 hours"/"kid friendly afternoon") → `plan_make`, built in place. Every plan change (make, re-plan, keep, new, applied edits) goes onto the undo stack (`PlanStore.applyPlan`). Stored per event in `DevicePrefs` (`PlanCodec` JSON, `PlanStore`), kept after leaving like hearts. Usage: no new metrics.

**Onboarding** (§7, `OnboardingScreen`): the first time an event is joined (`DevicePrefs.onboardingSeen`), and Options → **Tour**; Skip anywhere; never in screenshot mode. Cards: what you can do; location → permission; notifications → `POST_NOTIFICATIONS` (granting turns on organizer pushes); with the planner available, "Plan your day" with `PlanRequest.examples` ("Plan my <weekday> around <top tag> and <next tag>" — skipping general/free/music/demo and family tags —, "I've got 3 hours, show me the highlights", "Kid-friendly afternoon" when an item has a family tag, and the question "What's on near me right now?"): plan chips make the plan and open My plan, the question opens Ask.

**Live Update** (§8, `LiveFollow` + `live/`): **Follow on Lock Screen** per event in Options (default on once the event has hearts or a plan; `PlanStore.follows`), which also turns on **reminders**. Needs `POST_NOTIFICATIONS`.
- **Items of a day**: the plan's (that day), else hearted items, else the event's own items that day; minus Skipped. **Window**: 2 h before the first (with the event source also the event's start that day: "Gates open at 10:00 AM") to 30 min after the last end. Current = earliest-starting in progress; next; the one after. Modes BEFORE / ACTIVE / END_OF_DAY ("That's a wrap for today") / URGENT. Texts use absolute times (the chronometer counts down to the next start, `setWhen` + `setUsesChronometer` + `setChronometerCountDown`); chip text "7:00" / "Urgent"; walk to next = straight-line estimate from the current item's location (else the user's last location, ≤ 30 min old, within 8 km).
- **Notification** (`LiveNotifications.showLive`): channel `live_update` (IMPORTANCE_LOW, never MIN), ongoing, silent, `setRequestPromotedOngoing(true)`, `setShortCriticalText`, `ProgressStyle` (item segments in the brand purple, gaps `live_gap`, points at starts, progress = minutes since the first start; urgent → `BigTextStyle`), no colorize/custom views; manifest `POST_PROMOTED_NOTIFICATIONS`. Tap → the item (`attnd-open://<e>/schedule/<id>`), urgent → `update/<id>`, none → `plan`. Actions (no Heart, as on iOS): Skip next, Show on map (`attnd-open://<e>/map/<location>`; detail when the Map tab is hidden). Swiping it away = not reposted that day (`deleteIntent`). Options offers "Live Updates are off for Attnd. Turn on" when `canPostPromotedNotifications()` is false (opens `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`).
- **Driving** (`LiveUpdateController`, no server): recomputed whenever the bundle (join, refresh, `content_updated` push), hearts or `PlanStore` change (AppGraph), on alarms, actions, boot / app update / time zone change (`LiveUpdateReceiver`); next wake (`LiveFollow.nextWake`: window edges, item starts/ends, reminders, urgent expiry, ≥ every 15 min while shown) as `AlarmManager.setWindow(RTC_WAKEUP, target − 5 min, 10 min)` (inexact, no exact-alarm permission; Android stretches windows < 10 min). No venue geofence (removed with iOS's "Tell me when I arrive").
- **Reminders** (channel `reminders`): each hearted / planned item once, at start − (15 min + walk from the last location, ≤ 45 min), shown when ≤ 5 min away: "The Prairie Lights in 15 min · 2 min walk" / "Main Stage · 7:00 PM – 8:30 PM"; tap → the item.
- **Urgent (API-011)**: see Push Notifications; the Live Update shows the newest urgent block (push or bundle) for 12 h after it was sent or until **Dismiss**.
- **Reminders** have their own per-event setting (`PlanStore.reminders`, default = Follow on Lock Screen; the assistant's `reminders(on)`), so the controller runs reminders without the Live Update when only they are on.
- **Leave now** (assistant spec §6): with a plan and the user's last position, from the next planned item's departure time (`LiveAssist.runningLate`) the Live Update's title becomes "Leave now for X · 8 min walk" (chip "Leave") or "You can't make X in time"; a wake-up is scheduled for that time (`live.running_late`).

## Event assistant

The planner grows into an event assistant (`assistant_spec.md`; shared with iOS, which ports `domain/` 1:1 with the same
fixtures). Tests: `AssistantRouterTest` (exact block/action JSON per input), `AssistantBlocksTest`, `PlanEditingTest`,
`AssistantExtrasTest`, `AssistantFlowTest` (Android screen rules, transcript, shared plans), `QrCodeTest` (reference
hashes from python-qrcode), all on `AssistantFixture` = `PlannerFixture` + the "live music and BBQ" plan.
- **Rename** (§0): user-facing domain text says "liked" ("You liked this", "Because you liked X", "At X, which you liked",
  "2 liked items today", `EventSearch.HEARTED_TITLE` = "Liked"). Identifiers, storage keys and the API-009 metric names keep "heart".
- **Actions** (`AssistantActions.kt`): `ActionName` is the registry (key, typed params, `mutates`, `confirm` NONE / CHIP /
  SHEET, result type, `undoable`, `needsLocation`). `ActionRegistry.check` validates names and params against the bundle
  (ids exist, "HH:MM", event days, enums, readable ops). `ActionDispatcher` + the platform's `ActionExecutor`: CHIP actions
  from the assistant return a `confirm` block first, like/unlike/checkoff/plan changes run at once with an Undo
  (`undoFor`), never-available actions (leave event, debug mode, support copy) are refused, App Clip writes only like/unlike.
- **Blocks** (`AssistantBlocks.kt`): the §2 catalogue as `AssistantBlock`s with ids only; `BlockJson` encodes/decodes them
  (unknown types dropped, malformed known types reported); `BlockValidator` drops unknown ids, caps lists (carousel ≤ 8,
  list/timeline ≤ 20, pins ≤ 12, chips ≤ 5, text ≤ 400) and keeps ≤ 8 blocks with the chips last.
- **Router** (`AssistantRouter.route`): the command grammar (29 rules, in its KDoc), names resolved through `EventSearch`
  (`resolve`: filler words dropped, base score ≥ 3 × tokens, ties → "Which one do you mean?" chips), the layout rules
  (places, then items, then updates; 1 → card, 2–8 → carousel, more → list; a map with ≥ 2 places; lead entity chips), and
  `renderRead` for read actions. Plan edits come back with a `PlanEdit` preview and a `plan_edit` action.
- **Model path** (`AssistantModel`): `retrieve` (≤ 12), `prompt` (event facts, plan summary, likes, the last 6 turns +
  a keyword line, items), `parse` (first `{`…last `}`; invalid → `resolve` falls back to the router), `completeBlocks`
  for streaming, `AssistantConversation` (≤ 40 turns, stored with the plan per event).
- **Plan editing** (`PlanEditing.kt`): `PlanOp`s → `PlanConstraints` → `Planner.replan` (rules 13–20: window/score with
  constraints, excluded/pinned/food filters, origin, pinned must-includes first, walk limit, pace, meals, re-solve with
  frozen started/checked-off entries). With `PlanConstraints.NONE` every rule 1–12 result is unchanged. `PlanDiff`,
  `PlanHistory` (undo ≤ 20), `PlanVersions` (Chill = relaxed + walks ≤ 10 min, See everything = packed), `PlanOpJson`.
  `Plan` now carries `constraints` and `checkedOff` (in `PlanCodec`).
- **Walking** (`DayRoutes`): stops/legs/totals from the plan; `WalkRouter.dayLegs` makes one multi-waypoint
  `computeRoutes` call (≤ 25 stops, field mask `routes.legs.*`, cached per key) and `DayRoutes.withServiceLegs` applies
  the 2.5 × rule per leg.
- **Live** (`LiveAssist`): running late (leave by start − walk − 5 min, > 100 m away, ≤ 8 km), auto check-off (≥ 10 min
  within 150 m while it ran), skipped, gap filler (≥ 30 min, walk ≤ 10 min), like clash, recap, organizer changes.
- **Extras**: `WhyNot`, `PlanShare` (API-015 payload `{"v":1,"ids":[…],"d":"…"}`, base64url without padding, ≤ 1,500
  characters, lenient decode; `JoinLinks.parseShareLink` for `/p/<code>/<payload>`), `GroupPlan` (union of likes, + 1 per
  person via `groupLikes`). No Add to calendar (as on iOS): `add_to_calendar` is in `ActionName.NEVER_AVAILABLE`, a
  `calendar` block decodes as unknown, and "add X to my calendar" answers "Attnd doesn't add things to your calendar." (+ Share my plan).

**Plan tab** (plan tab spec, shared with iOS; replaces the conversation screen; tests `PlanTabTest`):
- **Tabs** (phase 4, iOS `TabLayout`; `domain/TabLayout.kt`, `DisplayConfig.layout`, `AppUiState.tabs`; tests `TabLayoutTest`,
  `PlanTabTest` tab cases, `NavigationControllerTest`): **Home** (`Home`) first and always the default, then the event's own
  tabs from the raw `display.tabs` (`home` accepted) / `default_tab`, at most five with Home (Flyer cut first, then Places
  when the Map is there); no My plan or Explore tab. `DisplayConfig.tabs` = the organizer tabs the layout shows (what every
  "is this tab visible" check reads). Places is titled "Places" (screenshot mode "Locations"). `selectedEventTab` (Home or a
  tab) → `selectedTab` (the organizer tab, null on Home). The Options gear sits on `optionsTab`'s root only: Home's header
  row, else the Updates tab's top bar, else the first tab's. Screenshot mode keeps today's tabs and default
  (`DisplayConfig.legacyLayout`, no Home). **Home** (`ui/screens/HomeScreen.kt`) is a temporary list of today's essentials
  (header with the gear, newest update, a My plan card, Happening now, Up next) until phase 5b draws the `home` layout's
  sections.
- **My plan** is a full-screen destination over the tabs (`AppUiState.planOpen`, Close / Back; `PlanDestination` in
  `ui/AttndApp.kt`) opened by the My plan button, Home's My plan card (on every phone until phase 6b gates the planner on
  the model), plan links, proposals and onboarding examples; phase 6b replaces it with the chat.
- **My plan's screen** (`MyPlanTab` in `ui/assistant/PlanTab.kt`, never a transcript): header (day switcher, `Planner.summary`,
  Share, Undo), live hints (running late, "Your plan changed" + Re-plan / Keep, recap, gap fillers, skipped chips), the
  day's timeline (walk legs, meals, check-offs, likes) and mini map; no plan: the bar focused, example chips, the liked
  items, progress while planning, the Gemini Nano download card. The **dock** at the bottom: answer card (≤ ⅓ of the
  window, scrolls inside, swipe down / ✕; live search results while typing; only the last turn), the proposal pill or the
  Undo pill (~8 s, `UndoOffer`), the input bar with the intent chip.
- **Proposals** (`PlanProposals`, `PreviewRows`, `ProposalText`): every plan edit (router, model, "Which one?" pick chips,
  hint / like-clash / running-late chips, compare "Use this version", shared `/p/` links) is previewed in the timeline, not
  applied: ADD → ≤ 4 dashed suggested rows where they'd go; REPLACE → the row crossed out with ≤ 4 options under it (the
  edit's choice + the item's Swap alternatives); CHANGE (remove / move / constraints / versions) → rows marked (crossed out,
  "Moves to 8:00 PM", added) + the diff pill ("−2 items, +1 lunch break"); SHARED → Use this plan / Merge with mine. Tap an
  option or Apply, or type "do it" / "yes" / "the second one" / "option 3" (`ProposalReply`); "no" / "cancel" / Esc / Back /
  Cancel clears it; anything else is a new turn. Applied through the dispatcher (MANUAL) with the undo stack; "undo" typed
  or the pill undoes. `AssistantHost.onProposal` switches to My plan.
- **Without on-device AI** the tab works on the router (plans from likes, commands, search, why-not); questions and
  requests it can't read get "Needs on-device AI" + search results (`PlanTabFlow.withoutModel`).
- **Turns** (`AssistantController`, owned by `EventViewModel`, `AssistantHost` = the VM): `AssistantFlow.path` — plan
  intents and commands go to the router (instant, deterministic); with Gemini Nano a question, no match or a 4+ word search
  goes to the model (`OnDeviceAssistant.turn`: streamed, `AssistantModel.completeBlocks` shown as they arrive, then `resolve`
  with the router as fallback). Without the model it runs on the router only. A turn's actions run through
  `ActionDispatcher` → the controller's `ActionExecutor` (likes with the API-009 count, plan edits / versions / undo via
  `PlanStore.applyPlan` / `undoPlan` (history ≤ 20), check-offs, settings (POST_NOTIFICATIONS requested first),
  share, open, directions); chips run with source RULES (CHIP actions confirm first), the UI's own buttons as MANUAL.
- **Stored per event** with the plan (kept after leaving): `PlanHistory`, the model's `AssistantConversation`, shared plans
  received (≤ 10). The answer card isn't restored (`AssistantTranscript` stays in the domain, unused by the UI).
- **Components** (`AssistantBlocksUi.kt`, `LocalBlockActions`): one composable per block type — text (bold), item / place /
  update cards (time + relative, place, image, distance + walk, like, "Open in Schedule" / "Show on map" / "Directions"),
  compact list (Show all), carousel, timeline (Now line, walk legs), mini map (`MiniMap`: lite-mode maps-compose, numbered
  stops, legs in the accent colour, dashed while estimated, user dot; `rememberServiceRoute` → `WalkRouter.dayLegs`), gallery
  (+ flyer), now/next, countdown, compare (Use this version), plan diff, plan (day timeline with real legs, check-offs, day
  total, map), setting (switch), summary, share (in-house QR `QrImage` + Share / Copy link), chips, confirm.
- **Share** (API-015): `PlanShare.planLink` / `itemLink` via the share sheet (`share.created`); `/p/` App Links
  (`android:pathPrefix="/p/"`, also from the QR scanner / code field) → `EventViewModel.openShareLink`: join the event, then
  a read-only shared plan turn (`share.opened` viewed / use / merge); "Use this plan" = `SharedPlans.use`, "Merge with mine" =
  `SharedPlans.merge` (`GroupPlan` over every shared plan received + own likes).
- **Live** (`LiveHintRules`): position samples only while the event UI is visible and a plan item is on within 2 h
  (`LiveLocationSampler` → `rememberUserLocation`, never a new permission prompt), hints refreshed every minute: running late
  banner on every tab (in My plan's timeline there; Directions / Swap for Y / Remove X previewed), auto check-off (`live.checkoff` auto, also a check toggle on
  started plan rows), skipped, gap fillers, recap; like clash after a like elsewhere → snackbar "Add to plan instead of X?" (Preview → My plan).
- **Elsewhere**: schedule details have Share. No "Ask about this" (removed as on iOS; the pure `AssistantFlow.askAbout`
  stays) and no Add to calendar.
- **Logging**: `assistant.turn` (path, intent, ms, blocks, fallback, `query_len`), `assistant.block` (debug), `assistant.action`
  (applied / confirmed / declined / undone / failed), `plan.edit`, `plan.undo`, `live.running_late`, `live.checkoff`,
  `share.created`, `share.opened` — never the typed text in production.

## Assistant (Gemini, on-device AI)

Everything is local: no server or API change, and the query only ever goes to the on-device model.
- **Open links**: `attnd-open://<event id|any>/<location/5|schedule/3|update/9|updates|event|search?q=…>` (`domain/OpenLinks.kt`) as the data of explicit intents to MainActivity (`OpenIntents.kt`, used by the Live Update and reminders; no intent filter declares the scheme). A link for another event than the joined one is ignored.
- **Removed, following iOS** (parity plan phase 2): the home-screen widget, AppFunctions (and KSP), dynamic shortcuts.
- **Planner**: see Planner, onboarding, Live Update; Ask requests that look like plans make one.
- **On-device model** (the event assistant's model path and the planner): Gemini Nano via ML Kit GenAI Prompt API **beta** (`data/OnDeviceAssistant.kt`: `turn` streams the assistant's JSON, `extractPreferences` for plans). Used only when `checkStatus()` says AVAILABLE; DOWNLOADABLE shows a Download card in My plan's empty state, DOWNLOADING its progress; otherwise the assistant runs on the rule-based router. `AssistantPrompt.context` still feeds `AssistantModel.retrieve`. Never in screenshot mode. The old Search | Ask toggle is gone.

## Logging & diagnostics (API-013, API-014)

Shared spec with iOS and attnd-web (record format, event names, redaction: attnd-web `LOG_EVENTS.md`; endpoints: `API_V2.md` → Diagnostics). Unit tests: `LoggingTest`, `LogRedactionTest`, `DiagnosticsUploadTest`, `AttndApiClientTest` (request ids).
- **Facade** `AppLog.debug(LogCategory.NET, LogEvent.REQUEST_FINISHED, "…", mapOf(…))` (`domain/AppLog.kt`, pure Kotlin so `data/api` logs too): level checked first, message and fields through `Redact`, then the sink. `AndroidLogSink` writes `android.util.Log` (tag `Attnd/<cat>`) and queues the record for one background writer thread → `LogStore` (`filesDir/logs/current.jsonl` + `previous.jsonl`, ~2 MB, newest kept, `seq` continues across restarts). Each record carries app/build/os/model, `fg`/`bg` (started activities), `wifi`/`cell`/`none`/`other`, the joined event and `diag`. **Use only names from `LogEvent` (all listed in `LOG_EVENTS.md`); add a row there first.** Caught errors: `AppLog.caught(cat, where, e, level)` → `error.caught` (`domain` = exception class, `code`, `type` = `AppLog.errorKind`, `where`).
- **Redaction** (`Redact`): never search/Ask/plan text (`query_len`; dev builds may add `query`, cut and masked), never tokens/keys/push tokens/JWTs/emails/device id/support code (keys dropped, values masked), URLs as path templates (`/api/v2/events/{id}/bundle/`, no host or query), coordinates 0.01° or distances only.
- **Every API call** (`AttndApi.exchange`) sends a new 16-hex `X-Request-Id` (also on the 401 retry, logged as `net.request.retry`) and, during a debug session, `X-Diag-Session`; logs `net.request.started` (trace) / `finished` (status, ms, bytes, `cache` 304/etag/none, attempt, req_id) / `failed` (`error` timeout/offline/dns/tls/…). `AttndApi.callListener` feeds the perf counts. Diagnostics uploads are not logged themselves.
- **Levels and debug mode** (`DiagnosticsController`, local `attnd_diagnostics` prefs, excluded from backup like `filesDir/logs/`): `BuildConfig.DEV` (dev and debug builds) → `debug`, one persistent debug code per install, continuous upload (`reason: "dev"`) in `dev` builds only (local `debug` builds never upload, `BuildConfig.UPLOAD_LOGS`; logcat, the ring buffer and the viewer only); release → `error` (errors only; `DebugMode.RELEASE_LEVEL`), local only, until Options → About & support → **Turn on debug logging for 10 minutes** (consent text, random `D-XXXXX` with Copy, countdown, Turn off; `debug` + upload from the moment it is turned on, nothing from before (no pre-roll; `DebugSession.uploadFromMs` = its start); the expiry is stored, so it ends on time across restarts; never linked to the device id, never remote).
- **Uploads** (`LogUploader`, `POST diagnostics/logs/`): the session's records after its stored cursor (seq per code) and from `uploadFromMs`, ≤ 500 per batch and ≤ 1 MB raw, gzip; every ~10 s in debug mode, ~30 s or 200 records in dev builds, and on background; `429`/`503` wait for `Retry-After`; `400`/`413` batches are skipped; failures back off 60 s and stay in the ring buffer. Logged as `logging.upload` (into the next batch).
- **Log viewer** (`LogViewerScreen`, from About & support): "Logs" (dev builds, debug mode) or "Recent logs" (production: what the ring buffer kept); filters errors only / level / category / text, newest first, tap for fields; Share writes the filtered lines to `cacheDir/log-exports/*.jsonl` and sends it through `FileProvider` `<package>.logs`.
- **Trace sections** (`android.os.Trace`, plus `perf.signpost` with `ms`): `launch` (process start / onCreate → first committed frame, `LaunchTiming`), `bundle_fetch`, `planner`, `map_first_render`.
- **Crash / ANR / exit reports** (`ExitReporter`, `POST diagnostics/reports/`, anonymous, every build): at launch (+8 s) `getHistoricalProcessExitReasons` newer than the last handled → `crash` (`REASON_CRASH*`, with the obfuscated Java stack our uncaught-exception handler saved in `filesDir/logs/crash.txt`, or that file alone), `anr` (`getTraceInputStream` thread dump), `low_memory`, `exit` (other system exits; exit-self, user-requested/stopped and app updates are skipped); `build` = versionCode (the mapping's key); `lifecycle.exit_reason` and `logging.report.sent` logged; retried next launch, dropped after 7 days.
- **API-014 perf counts** (`PerfRecorder`, into the usage batch; `domain/PerfMetrics.kt` has the exact bucket labels): `perf_launch_cold`/`_warm` (only into an event), `perf_bundle_fetch`/`perf_bundle_304`, `perf_api_error` (`4xx`/`5xx`/`timeout`/`offline`, diagnostics uploads excluded), `perf_push_refresh_delay` (push received → bundle applied), `perf_live_start_fail` (`denied`/`disabled`/`error`, once until it posts again), `perf_planner_ms` (the `Planner` run only), `perf_map_first_render` (first composition → `onMapLoaded`); each also logged as `perf.metric`. Counts outside an event wait for the next one (≤ 50).
- **Screenshot mode** is quiet: no uploads, no `X-Diag-Session`, no perf counts.
- **Symbols**: CI uploads R8's `mapping.txt` of every release build to Play (`play_publish.py --mapping`, `edits.deobfuscationfiles` `proguard`, same edit as the bundle; `--native-symbols` → `nativeCode` if a build ever has native symbols) and to attnd-web (`POST /api/diagnostics/symbols/?platform=android&version=…&build=…`, gzip; `ATTND_PROD_CI_KEY` on main, `ATTND_API_KEY` would be the dev server's but dev builds have no mapping), a failure there is only a warning.
- Play data safety: "Crash logs" and "Diagnostics" collected, not shared, not linked to identity; debug logs optional (user-started).

## API (v2)

Base `https://attnd.app/api/v2/` — see attnd-web `API_V2.md`.

| Call | Use |
|------|-----|
| `GET events/nearby/?lat&lng` | Launch screen "Find nearby events" |
| `GET events/lookup/?q=` | Event code / exact name (QR codes, App Links, referrer) |
| `GET events/<id>/bundle/` | Everything shown; `If-None-Match` → `304`; `X-Device-Id` header |
| `POST events/<id>/join/` | `{device_id, platform, push_token, app_version, preview_token?}` (API-026: `preview` in the answer) |
| `GET events/<id>/changes/?since=` | API-026 preview phone long-poll while resumed |
| `POST events/<id>/leave/` | `{device_id}`; queued in `DevicePrefs.pendingLeaves` and sent at once, at launch and after every successful refresh until the server accepts it (404 / 400 drop it too); rejoining the event drops it (`EventRepository.sendPendingLeaves`) |
| `POST devices/` | On launch and when push token / notification preference changes; `push_token` whenever there is one (omitted, never `""`, without one), `subscribed` = the opt-in |

Bundle refresh (API-007): live updates are **push-driven**. A `content_updated` push refreshes silently after a random delay (0…`jitter_ms` when the push sends it, API-026, clamped to 60 s, 0 = at once; else 0–10 s), only when its `content_version` is newer than the bundle's (`EventRepository.onContentUpdated`, works in the background too). Also on join, when the app is resumed, on pull-to-refresh, and a poll while the activity is resumed and an event is joined (`EventViewModel.foregroundSync`; conditional GET, usually `304`): a **5-minute safety poll** when the server has this device's push token (`EventRepository.hasPushToken`), otherwise **every 60 s** (no Firebase config, no Play services, token fetch or registration failed), and **every 15 s** while the last refresh failed — `LiveUpdates.pollIntervalMs`. **Network tidy-ups** (iOS, Nov 22): `app-status/` once per launch, then at most hourly; `POST devices/` only when the token, opt-in or joined event changed since the last registration the server accepted this launch (or it failed); foreground and poll refreshes are skipped within 15 s of a successful fetch (pushes, pull to refresh and joins always go). After every successful refresh `retryPendingServerWork` sends queued leaves and a failed device registration. Polls and push refreshes use `refresh(silent = true)` (no spinner); at these intervals the offline notice appears after the first failed poll (`LiveUpdates.failuresBeforeOfflineNotice`; any success clears it) and reads "Offline · last updated 2:14 PM" (last successful refresh, device time zone and 12/24-hour setting). A `404` on the bundle clears the event and returns to the launch screen.

**Preview phones (API-026)**: the console's **Preview on my phone** QR is the join link with `?preview=<token>`. A join link (App Link, scanned QR, pasted link; `JoinLinks.previewToken`: join links only, letters / digits / `-_.:`, ≤ 512) sends it once as `preview_token` with `join/` (never logged or shown: `Redact` masks `preview=` / `preview_token=` and drops `*token*` fields). `preview: true` in the answer makes this the event's preview phone (`EventRepository.previewEventId`, `DevicePrefs.previewEventId` in the non-backed-up device file); it ends on leaving, switching, a 404 or `preview: false` (a 4th preview phone joins as an ordinary attendee; an answer without the key keeps it). While the activity is resumed (`MainActivity`'s RESUMED block → `EventViewModel.previewSync` → `PreviewPoller`, never in screenshot mode) it long-polls `GET events/<id>/changes/?since=<content_version>` (`X-Device-Id`): changed or newer → `refresh(silent, source = "preview")` at once (15 s wait if that didn't move the version), else wait `retry_after` (≤ 3,600 s), errors 15 s, `preview: false` → stop (`PreviewPoll.next`). A green **Instant updates on** pill joins `EventStatusPills` meanwhile. Tests: `PreviewPhoneTest`.

**Event website (API-016)**: `event.website_url` (the organizer's Attnd Studio site, or null) → `Event.websiteUrl` via `WebLinks.httpsUrl` (missing, null, blank, non-`https` or host-less → null). When set, `EventInfoCard` (Options' event card and the search screen's event detail) shows a **Website** button that opens it with `Intent.ACTION_VIEW` + `CATEGORY_BROWSABLE` (the browser, never a WebView; nothing from the app is sent to it). Not on the top bar, so store screenshots don't change.

Models tolerate missing and unknown keys everywhere. Coordinates may be `null`: never pin those, and never treat `0,0` as real (`GeoPoint.of`). Schedule items may have no location ("General"). All times are shown in the event's `timezone`.

## Authentication

`Authorization: Bearer <token>`:
1. **Play Integrity JWT** — from `/api/v1/attest/android/` after verifying a Play Integrity token (production).
2. **`BuildConfig.ATTND_API_KEY`** — static fallback for dev/debug/emulator builds, from `local.properties` or the `ATTND_API_KEY` environment variable.

On a 401 the stored JWT is invalidated and the request is retried once after re-attesting (`AttndApi.send`). After an attestation failure, attestation is not retried for 10 minutes.

## Server-driven screens (API-024, API-027; phase 5a: domain only)

A 1:1 port of iOS `ScreenLayout.swift` + `Component.swift` (tests `ScreenLayoutTest` = iOS `ScreenLayoutTests`' in-scope cases + `HomeColumnWidths`, `ComponentTest` = iOS cases 1–3; attnd-web `events/tests_layout.py` where they overlap). Nothing draws it yet and `X-Attnd-Sections` is **not sent** (phase 5b turns on `SectionCatalogue.VERSION` = 2 and the renderer).
- **Decoding**: `ScreenLayouts.decode(JsonElement?)` for the raw `bundle.layout` (phase 1 keeps it as `BundleDto.layout`; 5b maps it onto `EventBundle`); lenient like the rest (bad sections / overrides / screens dropped one by one, type mismatches = missing). `LiveActivitySettings` is phase 1's (`EventContext.kt`).
- **Rules**: validate (known types per screen, once, parts / title 60 / limit 1–20 / ids ≤ 20 / text 300 / https image, ≤ 24), fallback to `ScreenLayout.DEFAULTS` (today's screens; the catalogue 2 tab and onboarding screens have none), overrides `category:` / `tag:` (first match, ≤ 8), Home always ends with `website`, visibility = a needed fact + every `when` condition (closed set; unknown = false, negated too). `LayoutSection.conditions` is the JSON's `when`.
- **Facts**: `ScreenFacts` reads the bundle, the API-018 `context` of the event / place / item (`context`, `context.menu`…) and `State` (now, model, plan, likes, within walk).
- **Chat**: `AssistantBlock.ComponentBlock` carries a screen component (`{"type":"menu","ids":["l6"]}`); `BlockJson.decode` reads screen types a chat may show, `BlockValidator` keeps one only while `ComponentCatalogue.valid`. The block UI draws nothing for it until 5b.

## Server-driven Display

`DisplayConfig` resolves the bundle's `display` object with the documented defaults:
tabs (order/visibility, Flyer only if listed; at most `DisplayConfig.MAX_TABS` = 5 in the listed order, never a "more" item) and default tab → `TabLayout` (Home `Home` first and the default, Map `Map`, Places `List`, Schedule `CalendarMonth`, Flyer `Description`, Updates `Notifications`; screenshot mode: today's tabs, no Home); `locations_style` cards/list/grouped; `schedule_style` timeline/by_day/by_location/now_next; `map_clustering` (maps-compose-utils `Clustering`); `map_fit_all` (LatLngBounds on open and on Map-tab reselect); `show_location_images`; `enable_search`; `group_by_category` (filter chips on Locations and Map); `secondary_pane` (beside/below the map on an unfolded foldable); `accent_color` → Material colour scheme `primary` (otherwise the brand purple: `#431F86` light / `#9D7BEA` dark, `ui/theme/Color.kt` + `res/values/colors.xml`; no Material You dynamic colour; `MaterialExpressiveTheme` on `expressiveLightColorScheme()` / `darkColorScheme()`); **`event.theme` (API-023) wins over `accent_color`** (`domain/Theme.kt` `ThemePalette.slots`, applied by `AttndTheme(eventTheme, accentColor, screenshot)`; tests `ThemeTest`, iOS `ThemeColors.swift`): per system appearance, primary → `primary` / `primaryContainer` / `surfaceTint`, secondary → `secondaryContainer` (34 % / 45 % over the surface) and the soft card wash `LocalThemeSoft` (14 % / 24 %, the server's `--color-brand-soft`; `themedElevatedCardColors()` on the assistant's item / place cards), tertiary → `tertiary` / `tertiaryContainer` and `LocalThemeHighlight` (progress bars, `themedProgressColor`); on-colours by the server's WCAG rule (white when ≥ 4.5:1, else ink `#120F22`, else the higher; container text stays on-surface when readable). Lenient per role: primary = this mode's, else `accent_color`, else the other mode's; secondary / tertiary = this mode's, else the other mode's; else the brand scheme. Screenshot mode keeps the brand purple (no theme, no accent). The Live Update's accent (`LiveTheme.accent`) is the theme's light primary, else `accent_color`, else the brand; urgent (the Live Update's urgent mode, urgent-channel notifications) is red `live_urgent`; `logo_url` at the start of the top app bar. Pull to refresh uses the Expressive loading indicator (`RefreshBox`), the Map's controls a vertical floating toolbar (with the search FAB below it when the navigation is a bar or absent), location detail actions a button group.

Status (phase 4, iOS `eventStatusBanners`): small floating pills (`StatusPills` in `ui/components/Banners.kt`, overlaid top-centre of the tab content, no layout space), built in one place (`domain/Banners.kt` `StatusBadges.all`, top to bottom): **Event ended** (`status == archived`), the refresh pill while `SyncState.error` is set ("Offline · Updated 2:14 PM · everything still works" / "Can't update · Updated 2:14 PM", `LiveUpdates.offlineNoticeText`, device time zone) and **Instant updates on** (green, the API-026 preview phone, `PreviewPoll.showsCapsule`). No Demo pill (as on iOS; `is_demo` is still decoded). Every pill and banner (the newest-update banner, the "update available" banner, the in-app banner) **swipes up to dismiss** (`SwipeUpToDismiss`, rule `SwipeDismissRule`: up 1:1, down rubber-bands ≤ 24 dp; released past 30 % of its height or flung up it slides off, else springs back; Reduce Motion = animator duration scale 0: shorter slide, no spring; TalkBack "Dismiss"); pills stay dismissed for the session (`LocalState.dismissedBadges`; a new refresh problem shows again) and Options → **Status** keeps the information. **In-app banner** (iOS `InAppBannerOverlay`): an organizer update pushed while the app is started (`AndroidLogSink.isForeground`) is posted silently (`setSilent`, the shade keeps it) and shown as a top card for 6 s (urgent 10 s: urgent pushes keep their system alert) through `AppGraph.inAppBanners`; tap → Updates at it; `ui.banner` logged.

## Foldables (adaptive layouts)

**Phones and foldables only; no tablets.** Tablets are excluded from distribution in Play Console's device catalog (manifest settings can't do it without also excluding unfolded foldables). Don't add tablet-specific layouts; the adaptive layouts below exist for unfolded foldables and simply also work on any large window. No orientation lock (Android 16 ignores it on large screens anyway).
- **Folded / cover screen (compact width)**: Material 3 Expressive short navigation bar (`NavigationSuiteScaffoldDefaults.navigationSuiteType`; horizontal items when the height is compact or in tabletop posture); Locations/Schedule show list *or* detail.
- **Unfolded inner display (medium width or more)**: `NavigationSuiteScaffold` switches to a collapsed wide navigation rail (the search / My plan button in its header); `ListDetailPaneScaffold` shows list + detail side by side; the Map tab shows beside `display.secondary_pane`, split exactly on the fold when a vertical hinge is reported (`FoldAwareSplit`).
- **Tabletop posture** (`currentWindowAdaptiveInfoV2().windowPosture.isTabletop`): map on the top half, pane on the bottom half, split on the horizontal hinge.
- Content respects display cutouts and system bars via `WindowInsets.safeDrawing`.

## Push Notifications (FCM)

`push/AttndMessagingService`:
- `onNewToken` → `POST devices/`.
- data `type=notification` → shows a notification on channel `event_updates` (tapping opens the Notifications tab at `notification_id`) and refreshes the bundle (silent).
- data `type=notification` + `priority=urgent` (API-011: data-only, Android priority high, TTL 12 h, `title`/`body`/`notification_id`/`event_id` in `data`, sent to every device in the event with a token) → `LiveNotifications.showUrgent` on channel `urgent_updates` (**Urgent announcements**, IMPORTANCE_HIGH) whenever `POST_NOTIFICATIONS` is granted, **even if the in-app opt-in is off**; tap → Updates at that notification (`notification_open` counted); the bundle is refreshed and the Live Update switches to urgent mode (`LiveUpdateController.onUrgent`). We never send `live_activity_token` (iOS only).
- data `type=content_updated` (`event_id`, `content_version`; FCM data values are strings) → `EventRepository.onContentUpdated`: silent refresh after a random delay (0…`jitter_ms`, API-026; else 0–10 s) if the version is newer (API-007). The server sends it to **every** device in the event with a token, opted in or not (debounced, collapsible, normal priority).

The FCM token is always fetched and sent with `POST devices/` at launch (and with join) when Firebase is configured, whatever the opt-in; data messages need no permission. Visible notifications are opt-in from **Options**, which requests `POST_NOTIFICATIONS`; the preference is sent as `subscribed`. A token the server hasn't confirmed is re-sent from the foreground poll loop.

**Setup** (not in the repo):
1. Create/choose a Firebase project and add Android apps `com.arcu.attnd` **and** `com.arcu.attnd.debug` (the google-services plugin fails a variant whose package is missing).
2. Download `google-services.json` to `app/google-services.json` (git-ignored). In CI, write it from a secret before building.
3. Give the attnd-web backend an FCM HTTP v1 service account (Firebase console → Project settings → Service accounts) so it can send pushes.

Without `google-services.json` the build still succeeds; the plugin isn't applied, Firebase is never initialised, and the app skips all push code paths.

## Deep Linking

- **App Links**: `https://attnd.app/join/<code>/` opens the app (`/.well-known/assetlinks.json` is served by attnd-web). `MainActivity` hands the code to the ViewModel, which looks it up, leaves any current event and joins the new one. Shared plans `https://attnd.app/p/<code>/<payload>` (API-015, same filter) join the event and show the plan in the assistant.
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

**Lean mode now (Nov 15, 2026, private repo minutes):** the pipeline never runs on push or pull requests; it runs only when started by hand (Actions → Android → Run workflow) and only when the owner asks for an Android build. The integration matrix additionally needs the repo variable `CI_FULL=true`. Full pipeline below and how to restore it: attnd-web `docs/CI_PIPELINES.md`.

| Trigger | What happens |
|---------|--------------|
| Every run | In parallel: **Unit tests & lint** (`testDebugUnitTest lintDebug`, plus `assembleDebug` on pull requests; then `scripts/ci/lint_summary.py` prints lint's findings as warnings, never failing), **Integration · dev** (`dev.attnd.app`, secret `ATTND_API_KEY`) and **Integration · prod** (`attnd.app`, secret `ATTND_PROD_CI_KEY`, accepted for the DEMO event only): `testDebugUnitTest -PintegrationTests`, then `scripts/ci/junit_summary.py`. The integration jobs never gate delivery; prod is `continue-on-error` (a failure is a warning). A missing secret skips that job's tests |
| Push to `develop` | Also in parallel: **Signed bundle** (`bundleDev -x lintVitalDev`: the `dev` build type, against the `dev` environment's `ATTND_SERVER`; `app.aab` kept as the `android-bundle` artifact), then **Publish to Play internal testing** (Linux, `needs: [test, bundle]`, so failing unit tests block it) |
| Push to `main` | `bundleRelease -x lintVitalRelease` against `attnd.app`; the artifact also holds R8's `mapping.txt`, which the bundle job posts to attnd-web (`POST /api/diagnostics/symbols/`, `ATTND_PROD_CI_KEY`; a failure is a warning) → **Publish to Play production** (staged rollout if `PLAY_ROLLOUT` < 1), with the store listing text and the mapping (`edits.deobfuscationfiles`) in the same edit |

**Gradle caches**: every job restores `~/.gradle/caches` (including the Gradle build cache, `org.gradle.caching=true`) and `~/.gradle/wrapper` with `actions/cache`, keyed per job (`gradle-test-…`, `gradle-bundle-…`, `gradle-integration-…`) on the build files' hash plus the commit, falling back to the newest earlier cache; pushes save a fresh one (pull requests only read). The project's `.gradle/` (configuration cache) is never cached, so signing settings never land in a cache.

`play_publish.py`'s bundle mode takes `--mapping mapping.txt` and `--native-symbols <zip>` (both uploaded for the bundle's version code before the commit). Docs-only commits (`*.md`) don't run the pipeline; a newer push to `develop` cancels the older run (`main` never cancels). Gradle: parallel, build cache, configuration cache, 4 GB heap (`gradle.properties`). Publishing uses `scripts/ci/play_publish.py` (Play Developer API v3, service-account JWT) — no third-party actions or Gradle plugins. Release builds never contain the static API key. Server host: `ATTND_SERVER` → `BuildConfig.ATTND_SERVER` (default `attnd.app`). Setup checklist: `SETUP.md` in attnd-web.

## Building

```bash
./gradlew assembleDebug        # debug
./gradlew bundleDev            # dev (release-signed, not minified; requires signing.properties)
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
- **Screenshot mode** (debug builds only, `domain/ScreenshotRequest.kt`): `adb shell am start -n com.arcu.attnd.debug/com.arcu.attnd.MainActivity --es attnd_screenshot <launch|map|locations|location|schedule|notifications>` (optional `attnd_screenshot_event`, default `DEMO`; `attnd_screenshot_location`, default "Main Stage"). It joins the event through the normal lookup/join path and shows that screen with no launch screen flash, update banner/screen, Demo pill, offline notice, snackbar or usage counts; the map shot fits every pin without clustering. It keeps today's tabs (Map / Locations / Schedule / Updates, `DisplayConfig.legacyLayout`), never Home or My plan. Keep it working when changing navigation or join.
- **Screenshot pipeline**: builds the debug APK against the dev server (`ATTND_API_KEY` repo secret, `MAPS_API_KEY`) with `-PstoreScreenshots`: package `com.arcu.attnd`, signed with the upload key (`ANDROID_KEYSTORE_*`), because the Maps key only accepts the real app's package and certificates (otherwise the map is blank); the APK never leaves the emulator. It boots a headless `android-37.0;google_apis;x86_64` emulator (Pixel 9 Pro XL profile, KVM, emulator system libraries such as libpulse installed by the script, demo-mode status bar at 9:41) and captures `build/screenshots/raw/<screen>.png` (`screenshots.sh`); `compose_screenshots.py` makes 2160×3840 phone screenshots (purple gradient, caption, framed screenshot), the 1024×500 feature graphic and the 512×512 icon (from `drawable-nodpi/attnd_logo.png`) in `build/store/`.
- **`play_publish.py`**: `--listing DIR` (text), `--images DIR` (+ `--images-only`), or an `.aab` with `--listing` (release + text in one edit). A commit withdraws anything already in review and resubmits it together. Use `--not-for-review` if Play requires changes to be sent from the Console (after a rejection).
- Python for CI scripts runs in a venv (Pillow, `pyjwt[crypto]`); `__pycache__/` is git-ignored.

## Release

See `RELEASE_SETUP.md` for Play Store upload instructions.

## Related Repos

- **[attnd-web](https://github.com/apbeers/Attnd-Web)** — Django backend + REST API, and the **coordinating repo**: `API_V2.md` (contract), `API_CHANGES.md` (tracker)
- **[attnd-ios](https://github.com/apbeers/Attnd-iOS)** — SwiftUI iOS client
